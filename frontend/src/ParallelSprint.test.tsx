import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import ParallelSprint from './ParallelSprint'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const base = '/api/competitions/1/disciplines/3/categories/2'
const heat = {id: 7, version: 5, round: 1, number: 1, first: {id: 4, name: 'Alpha', startNumber: 1}, second: {id: 5, name: 'Bravo', startNumber: 2}, firstStatus: null, secondStatus: null, firstTimeCentiseconds: null, secondTimeCentiseconds: null, winnerSlot: null, winnerOverridden: false}
beforeEach(() => {
  window.location.hash = '#/parallel-sprint?competition=1&discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (p === `${base}/bracket`) return {id: 8, heats: [heat]}
    throw new Error(p)
  })
})
afterEach(() => {cleanup(); vi.resetAllMocks(); window.location.hash = ''})
test('read-only users see positions and no editing controls', async () => {
  render(<ParallelSprint readOnly/>)
  expect(await screen.findByText('Position 1: 1 — Alpha · — · —')).toBeInTheDocument()
  expect(screen.queryByText('Save heat')).not.toBeInTheDocument()
  expect(screen.queryByText('Seed bracket')).not.toBeInTheDocument()
})
test('saves a versioned heat and confirms a manual winner', async () => {
  vi.mocked(csrfRequest).mockResolvedValue({})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.change((fireEvent.click(await screen.findByText('Open heat 1 form')), await screen.findByLabelText('Time Position 1 — Alpha')), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time Position 2 — Bravo'), {target: {value: '1:15.00'}})
  fireEvent.mouseDown(screen.getByLabelText('Winner override')); fireEvent.click(await screen.findByRole('option', {name: 'Bravo'}))
  fireEvent.click(screen.getByText('Save heat'))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(`${base}/brackets/8/heats/7/result`, 'PUT', {version: 5, first: {status: 'FINISHED', timeCentiseconds: 7425}, second: {status: 'FINISHED', timeCentiseconds: 7500}, winnerCrewId: 5, confirmed: true}))
  expect(window.confirm).toHaveBeenCalled()
})
test('conflict refreshes server heat while preserving the draft', async () => {
  vi.mocked(csrfRequest).mockRejectedValue(new ApiError(409, {}))
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.change((fireEvent.click(await screen.findByText('Open heat 1 form')), await screen.findByLabelText('Time Position 1 — Alpha')), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time Position 2 — Bravo'), {target: {value: '1:15.00'}})
  fireEvent.click(screen.getByText('Save heat'))
  expect(await screen.findByRole('alert')).toHaveTextContent('draft has been kept')
  expect(screen.getByLabelText('Time Position 1 — Alpha')).toHaveValue('1:14.25')
  expect(screen.getByText('Save heat')).toBeDisabled()
})

test('locks selection and inputs while a save is pending', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(csrfRequest).mockImplementation(() => new Promise(resolve => {finish = resolve}))
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.change((fireEvent.click(await screen.findByText('Open heat 1 form')), await screen.findByLabelText('Time Position 1 — Alpha')), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time Position 2 — Bravo'), {target: {value: '1:15.00'}})
  fireEvent.click(screen.getByText('Save heat'))
  expect(screen.getByLabelText('Competition')).toHaveAttribute('aria-disabled', 'true')
  expect(screen.getByLabelText('Time Position 1 — Alpha')).toBeDisabled()
  finish({})
  await waitFor(() => expect(screen.getByLabelText('Time Position 1 — Alpha')).not.toBeDisabled())
})

test('rejects a timed discipline in a direct parallel link without requesting a bracket', async () => {
  window.location.hash = '#/workspace/1/parallel-sprint?discipline=8&category=2'
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions/1') return {id: 1, title: 'Race'}
    if (p.endsWith('/disciplines')) return [{id: 8, name: 'Timed', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    throw new Error(p)
  })
  render(<ParallelSprint fixedCompetition="1" readOnly/>)
  await waitFor(() => expect(window.location.hash).toBe('#/workspace/1/parallel-sprint?category=2'))
  expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.includes('/bracket'))).toBe(false)
})

test('selects an available round after changing bracket', async () => {
  window.location.hash = '#/workspace/1/parallel-sprint?discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions/1') return {id: 1, title: 'Race'}
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2, 4]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 4, displayName: 'R6'}]
    if (p.includes('/categories/2/bracket')) return {id: 8, heats: [heat, {...heat, id: 9, round: 2}]}
    if (p.includes('/categories/4/bracket')) return {id: 9, heats: [{...heat, id: 10, round: 1}]}
    throw new Error(p)
  })
  render(<ParallelSprint fixedCompetition="1" readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Choose round'))
  fireEvent.click(await screen.findByRole('option', {name: 'Round 2'}))
  fireEvent.mouseDown(screen.getByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R6'}))
  await waitFor(() => expect(screen.getByLabelText('Choose round')).toHaveTextContent('Round 1'))
})

test('keeps a heat draft when filter changes are declined and warns before reload', async () => {
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}, {id: 9, title: 'Other'}], totalPages: 1}
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2, 4]}, {id: 5, name: 'Another', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 4, displayName: 'R6'}]
    if (p === `${base}/bracket`) return {id: 8, heats: [heat]}
    throw new Error(p)
  })
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.click(await screen.findByText('Open heat 1 form'))
  const time = screen.getByLabelText('Time Position 1 — Alpha')
  fireEvent.change(time, {target: {value: '1:14.25'}})
  fireEvent.mouseDown(screen.getByLabelText('Winner override'))
  fireEvent.click(await screen.findByRole('option', {name: 'Bravo'}))
  for (const [label, value] of [['Competition', 'Other'], ['Discipline', 'Another'], ['Category', 'R6']]) {
    fireEvent.mouseDown(screen.getByLabelText(label))
    fireEvent.click(await screen.findByRole('option', {name: value}))
    expect(time).toHaveValue('1:14.25')
    expect(screen.getByLabelText('Winner override')).toHaveTextContent('Bravo')
  }
  expect(confirm).toHaveBeenCalledTimes(3)
  const unload = new Event('beforeunload', {cancelable: true})
  expect(window.dispatchEvent(unload)).toBe(false)
  window.dispatchEvent(new Event('result-completed'))
  expect(window.dispatchEvent(new Event('beforeunload', {cancelable: true}))).toBe(true)
})

test.each(['CREATED', 'STARTED', 'COMPLETED'])('offers seeding only when discipline is started (%s)', async status => {
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', status, categoryIds: [2]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (p === `${base}/bracket`) return {id: null, heats: []}
    throw new Error(p)
  })
  render(<ParallelSprint readOnly={false}/>)
  await screen.findByText('No bracket has been seeded.')
  if (status === 'STARTED') expect(screen.getByRole('button', {name: 'Seed bracket'})).toBeEnabled()
  else expect(screen.queryByRole('button', {name: 'Seed bracket'})).not.toBeInTheDocument()
})
