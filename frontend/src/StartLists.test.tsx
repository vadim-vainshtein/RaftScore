import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import StartLists from './StartLists'
import {apiRequest, csrfRequest} from './api/client'
import {wallTimeToInstant} from './i18n/wall-time'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const base = '/api/competitions/1/disciplines/3/categories/2/start-list'
let locked = false
let startEmpty = false
let disciplineStatus = 'STARTED'
beforeEach(() => {
  window.location.hash = '#/start-lists?competition=1&discipline=3'; locked = false; startEmpty = false; disciplineStatus = 'STARTED'
  vi.mocked(csrfRequest).mockReset().mockResolvedValue({id: 7, locked: false, slots: []})
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race', startDate: '2026-01-01', timeZone: 'Asia/Almaty', state: 'STARTED'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', status: disciplineStatus, categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/private')) return [{id: 4, categoryId: 2, crewName: 'A', startNumber: 1, eligible: true}, {id: 5, categoryId: 2, crewName: 'B', startNumber: 2, eligible: true}]
    if (path === base && startEmpty) return {id: null, locked: false, slots: []}
    if (path === base) return {id: 7, locked, slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1, plannedTime: null}, {crewId: 5, crewName: 'B', startNumber: 2, position: 2, plannedTime: null}]}
    throw new Error(path)
  })
})
afterEach(() => {cleanup(); vi.restoreAllMocks(); window.location.hash = ''})
async function selectCategory() {
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('Position')
}
test('read-only view omits private requests and mutation controls', async () => {
  render(<StartLists readOnly/>); await selectCategory()
  expect(screen.getByText('A')).toBeInTheDocument()
  expect(screen.queryByText('Generate start list')).not.toBeInTheDocument()
  expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.endsWith('/private'))).toBe(false)
})
test('manual ordering submits UTC timing after confirmation', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<StartLists readOnly={false}/>); await selectCategory()
  fireEvent.mouseDown(screen.getByLabelText('Order')); fireEvent.click(await screen.findByRole('option', {name: 'Manual'}))
  fireEvent.click(screen.getByRole('button', {name: 'Move up: B'}))
  fireEvent.change(screen.getByLabelText(/First start time \(Asia\/Almaty\)/), {target: {value: '10:00'}})
  fireEvent.change(screen.getByLabelText('Interval (seconds)'), {target: {value: '60'}})
  fireEvent.click(screen.getByText('Generate start list'))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(base, 'PUT', {random: false, reverseOverall: false, crewIds: [5, 4], firstStart: '2026-01-01T05:00:00.000Z', intervalSeconds: 60}))
})
test('locked list requires confirmation to clear results', async () => {
  locked = true; vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  render(<StartLists readOnly={false}/>); await selectCategory()
  expect(screen.getByText('Generate start list')).toBeDisabled()
  fireEvent.click(screen.getByText('Clear results')); expect(csrfRequest).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('Clear results'))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(`${base}/source-results`, 'DELETE'))
})
test('a concurrent results conflict locks the controls and refreshes the list', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.mocked(csrfRequest).mockImplementation(async () => {locked = true; throw new (await import('./api/client')).ApiError(409, {})})
  render(<StartLists readOnly={false}/>); await selectCategory()
  fireEvent.change(screen.getByLabelText(/First start time/), {target: {value: '10:00'}})
  fireEvent.click(screen.getByText('Generate start list'))
  await waitFor(() => expect(screen.getByText('Locked: results have been entered.')).toBeInTheDocument())
  expect(screen.getByText('Generate start list')).toBeDisabled()
})

test('wall times reject DST gaps and choose the earlier overlap', () => {
  expect(wallTimeToInstant('2026-03-08T02:30', 'America/New_York')).toBeNull()
  expect(wallTimeToInstant('2026-11-01T01:30', 'America/New_York')?.toISOString()).toBe('2026-11-01T05:30:00.000Z')
})
test('holds selection during mutation and immediately uses the returned list', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  startEmpty = true
  let resolve!: (value: unknown) => void
  vi.mocked(csrfRequest).mockImplementationOnce(() => new Promise(r => {resolve = r}))
  render(<StartLists readOnly={false}/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('No start list yet.')
  fireEvent.change(screen.getByLabelText(/First start time/), {target: {value: '10:00'}})
  fireEvent.click(screen.getByText('Generate start list'))
  expect(screen.getByLabelText('Competition')).toHaveAttribute('aria-disabled', 'true')
  resolve({id: 7, locked: false, slots: []})
  await waitFor(() => expect(screen.getByText('Generate start list')).toBeEnabled())
  fireEvent.click(screen.getByText('Generate start list'))
  expect(confirm).toHaveBeenCalledTimes(1)
})

test('does not offer result clearing for a completed discipline', async () => {
  locked = true; disciplineStatus = 'COMPLETED'
  render(<StartLists readOnly={false}/>); await selectCategory()
  expect(screen.getByText('Locked: results have been entered.')).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Clear results'})).not.toBeInTheDocument()
  expect(screen.getByRole('button', {name: 'Generate start list'})).toBeDisabled()
})

test('defaults date and interval, requires time, and submits editable timing', async () => {
  startEmpty = true
  render(<StartLists readOnly={false}/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('No start list yet.')
  expect(screen.getByLabelText(/First start date/)).toHaveValue('2026-01-01')
  expect(screen.getByLabelText(/First start time \(Asia\/Almaty\)/)).toHaveValue('')
  expect(screen.getByLabelText('Interval (seconds)')).toHaveValue(120)
  fireEvent.click(screen.getByText('Generate start list'))
  expect(csrfRequest).not.toHaveBeenCalled()
  expect(screen.getByRole('alert')).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText(/First start date/), {target: {value: '2026-01-02'}})
  fireEvent.change(screen.getByLabelText(/First start time \(Asia\/Almaty\)/), {target: {value: '10:00'}})
  fireEvent.click(screen.getByText('Generate start list'))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(base, 'PUT', expect.objectContaining({firstStart: '2026-01-02T05:00:00.000Z', intervalSeconds: 120})))
})

test('resets timing when selecting another category and preserves it after a failed request', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', status: 'STARTED', categoryIds: [2, 6]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 6, displayName: 'R6'}]
    if (path.endsWith('/categories/6/start-list')) return {id: null, locked: false, slots: []}
    return original(path)
  })
  startEmpty = true
  vi.mocked(csrfRequest).mockRejectedValueOnce(new Error('offline'))
  render(<StartLists readOnly={false}/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('No start list yet.')
  fireEvent.change(screen.getByLabelText(/First start time/), {target: {value: '10:00'}})
  fireEvent.click(screen.getByText('Generate start list'))
  await screen.findByRole('alert')
  expect(screen.getByLabelText(/First start time/)).toHaveValue('10:00')
  fireEvent.mouseDown(screen.getByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R6'}))
  await screen.findByText('No start list yet.')
  expect(screen.getByLabelText(/First start time/)).toHaveValue('')
  expect(screen.getByLabelText(/First start date/)).toHaveValue('2026-01-01')
  expect(screen.getByLabelText('Interval (seconds)')).toHaveValue(120)
})

test('automatically selects the only discipline and eligible category', async () => {
  window.location.hash = '#/workspace/1/start-lists'
  render(<StartLists fixedCompetition="1" readOnly/>)
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(base))
  expect(screen.getByLabelText('Discipline')).toHaveTextContent('Sprint')
  expect(screen.getByLabelText('Category')).toHaveTextContent('R4')
  expect(window.location.hash).toContain('discipline=3')
  expect(window.location.hash).toContain('category=2')
})

test('preserves valid linked selections when multiple options exist', async () => {
  window.location.hash = '#/workspace/1/start-lists?discipline=3&category=2'
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async (path, options) => {
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', categoryIds: [2, 9]}, {id: 4, name: 'Slalom', categoryIds: [9]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 9, displayName: 'R6'}]
    return original(path, options)
  })
  render(<StartLists fixedCompetition="1" readOnly/>)
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(base))
  expect(screen.getByLabelText('Discipline')).toHaveTextContent('Sprint')
  expect(screen.getByLabelText('Category')).toHaveTextContent('R4')
})

test.each([
  ['Sprint', 'R4', '2'],
  ['Slalom', 'R6', '9'],
])('retains a linked category until choosing %s, then applies compatibility', async (chosen, expectedCategory, expectedId) => {
  window.location.hash = '#/workspace/1/start-lists?category=2'
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async (path, options) => {
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', categoryIds: [2]}, {id: 4, name: 'Slalom', categoryIds: [9]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 9, displayName: 'R6'}]
    if (path.endsWith('/categories/9/start-list')) return {id: null, locked: false, slots: []}
    return original(path, options)
  })
  render(<StartLists fixedCompetition="1" readOnly/>)
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/competitions/1/categories'))
  await screen.findByLabelText('Discipline')
  expect(window.location.hash).toContain('category=2')
  fireEvent.mouseDown(screen.getByLabelText('Discipline'))
  fireEvent.click(await screen.findByRole('option', {name: chosen}))
  await waitFor(() => expect(screen.getByLabelText('Category')).toHaveTextContent(expectedCategory))
  expect(window.location.hash).toContain(`category=${expectedId}`)
})
