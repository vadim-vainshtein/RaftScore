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
test('identifies both finals and explains older brackets', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  const secondSemi = {...heat, id: 8, number: 2, first: {...heat.first, id: 6}, second: {...heat.second, id: 7}}
  vi.mocked(apiRequest).mockImplementation(async p => p === `${base}/bracket`
    ? {id: 8, heats: [heat, secondSemi, {...heat, id: 9, round: 2, number: 1}, {...heat, id: 10, round: 2, number: 2}]}
    : original(p))
  const {unmount} = render(<ParallelSprint readOnly/>)
  expect(await screen.findByText('Final B (places 3–4)')).toBeInTheDocument()
  expect(screen.getByText('Final A (places 1–2)')).toBeInTheDocument()
  unmount()
  vi.mocked(apiRequest).mockImplementation(async p => p === `${base}/bracket`
    ? {id: 8, heats: [heat, secondSemi, {...heat, id: 9, round: 2, number: 1}]}
    : original(p))
  render(<ParallelSprint readOnly/>)
  expect(await screen.findByText(/created under the previous rules/)).toBeInTheDocument()
})
test('shows a resolved empty final slot as a bye', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async p => p === `${base}/bracket`
    ? {id: 8, heats: [{...heat, round: 2, first: null, firstResolved: true, secondResolved: false}]}
    : original(p))
  render(<ParallelSprint readOnly/> )
  expect(await screen.findByText(/Position 1:.*bye/i)).toBeInTheDocument()
})
test('annuls a played heat with a reason and shows dependent heats', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  const played = {...heat, firstStatus: 'FINISHED', secondStatus: 'FINISHED', firstTimeCentiseconds: 100, secondTimeCentiseconds: 120, winnerSlot: 1}
  const final = {...played, id: 9, round: 2, number: 1}
  vi.mocked(apiRequest).mockImplementation(async p => p === `${base}/bracket` ? {id: 8, heats: [played, final]} : original(p))
  vi.mocked(csrfRequest).mockRejectedValue(new ApiError(409, {code: 'parallel.dependent_heats', parameters: {heatIds: [9]}}))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.spyOn(window, 'prompt').mockReturnValue('Timing error')
  render(<ParallelSprint readOnly={false}/> )
  const buttons = await screen.findAllByRole('button', {name: 'Annul heat result'})
  fireEvent.click(buttons[0])
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(`${base}/brackets/8/heats/7/annulment`, 'POST', {version: 5, reason: 'Timing error'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Annul these dependent heats first')
})
test('upgrades an unfinished legacy bracket only after confirmation', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  const secondSemi = {...heat, id: 8, number: 2, first: {...heat.first, id: 6}, second: {...heat.second, id: 7}}
  const legacy = {id: 8, heats: [heat, secondSemi, {...heat, id: 9, round: 2, number: 1}]}
  vi.mocked(apiRequest).mockImplementation(async p => p === `${base}/bracket` ? legacy : original(p))
  vi.mocked(csrfRequest).mockResolvedValue({...legacy, heats: [...legacy.heats, {...heat, id: 10, round: 2, number: 2}]})
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<ParallelSprint readOnly={false}/> )
  fireEvent.click(await screen.findByRole('button', {name: 'Add final B'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(`${base}/bracket/bronze-final`, 'POST'))
  expect(await screen.findByText('Final B (places 3–4)')).toBeInTheDocument()
})
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
