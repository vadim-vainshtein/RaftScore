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
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', categoryIds: [2]}]
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
  fireEvent.change(await screen.findByLabelText('Time 1'), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time 2'), {target: {value: '1:15.00'}})
  fireEvent.mouseDown(screen.getByLabelText('Winner override')); fireEvent.click(await screen.findByRole('option', {name: 'Bravo'}))
  fireEvent.click(screen.getByText('Save heat'))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(`${base}/brackets/8/heats/7/result`, 'PUT', {version: 5, first: {status: 'FINISHED', timeCentiseconds: 7425}, second: {status: 'FINISHED', timeCentiseconds: 7500}, winnerCrewId: 5, confirmed: true}))
  expect(window.confirm).toHaveBeenCalled()
})
test('conflict refreshes server heat while preserving the draft', async () => {
  vi.mocked(csrfRequest).mockRejectedValue(new ApiError(409, {}))
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.change(await screen.findByLabelText('Time 1'), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time 2'), {target: {value: '1:15.00'}})
  fireEvent.click(screen.getByText('Save heat'))
  expect(await screen.findByRole('alert')).toHaveTextContent('draft has been kept')
  expect(screen.getByLabelText('Time 1')).toHaveValue('1:14.25')
  expect(screen.getByText('Save heat')).toBeDisabled()
})

test('locks selection and inputs while a save is pending', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(csrfRequest).mockImplementation(() => new Promise(resolve => {finish = resolve}))
  render(<ParallelSprint readOnly={false}/>)
  fireEvent.change(await screen.findByLabelText('Time 1'), {target: {value: '1:14.25'}})
  fireEvent.change(screen.getByLabelText('Time 2'), {target: {value: '1:15.00'}})
  fireEvent.click(screen.getByText('Save heat'))
  expect(screen.getByLabelText('Competition')).toHaveAttribute('aria-disabled', 'true')
  expect(screen.getByLabelText('Time 1')).toBeDisabled()
  finish({})
  await waitFor(() => expect(screen.getByLabelText('Time 1')).not.toBeDisabled())
})
