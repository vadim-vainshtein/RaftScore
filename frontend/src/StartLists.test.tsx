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
beforeEach(() => {
  window.location.hash = '#/start-lists?competition=1&discipline=3'; locked = false; startEmpty = false
  vi.mocked(csrfRequest).mockReset().mockResolvedValue({id: 7, locked: false, slots: []})
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race', timeZone: 'Asia/Almaty'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', categoryIds: [2]}]
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
  fireEvent.change(screen.getByLabelText('First start (Asia/Almaty)'), {target: {value: '2026-01-01T10:00'}})
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
  fireEvent.click(screen.getByText('Generate start list'))
  expect(screen.getByLabelText('Competition')).toHaveAttribute('aria-disabled', 'true')
  resolve({id: 7, locked: false, slots: []})
  await waitFor(() => expect(screen.getByText('Generate start list')).toBeEnabled())
  fireEvent.click(screen.getByText('Generate start list'))
  expect(confirm).toHaveBeenCalledTimes(1)
})
