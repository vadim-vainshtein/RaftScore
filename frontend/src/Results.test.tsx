import {act, cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Results from './Results'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const path = '/api/competitions/1/disciplines/3/categories/2/results'
beforeEach(() => {
  window.location.hash = '#/results?competition=1&discipline=3'
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', categoryIds: [2]}]
    if (p.endsWith('/tiebreak')) return {disciplineId: null}
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (p.endsWith('/start-list')) return {slots: [{crewId: 4}]}
    if (p === path) return [{crewId: 4, crewName: 'Crew A', regionOrClub: 'Club', startNumber: 1, place: 1, totalCentiseconds: 7425, status: 'FINISHED', points: 100}]
    throw new Error(p)
  })
})
afterEach(() => {cleanup(); vi.restoreAllMocks(); window.location.hash = ''})
test('shows category results from the public projection', async () => {
  render(<Results readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  expect(await screen.findByText('1 — Crew A')).toBeInTheDocument()
  expect(screen.getByText('Time: 1:14.25')).toBeInTheDocument()
  expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.includes('private') || p.includes('attempt-grid'))).toBe(false)
})
test('keeps the last results when refresh fails', async () => {
  const interval = vi.spyOn(window, 'setInterval')
  render(<Results readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('1 — Crew A')
  vi.mocked(apiRequest).mockRejectedValue(new Error('offline'))
  await act(async () => { (interval.mock.calls.find(call => call[1] === 10000)?.[0] as () => void)(); await Promise.resolve() })
  await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  expect(screen.getByText('1 — Crew A')).toBeInTheDocument()
})



test('full access can edit the selected category and recalculate', async () => {
  vi.mocked(csrfRequest).mockResolvedValue(undefined)
  render(<Results readOnly={false}/> )
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  await screen.findByText('1 — Crew A')
  expect(screen.getByRole('link', {name: 'Edit result'})).toHaveAttribute('href', '#/result-grid?competition=1&discipline=3&category=2')
  fireEvent.click(screen.getByRole('button', {name: 'Recalculate'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/recalculate', 'POST'))
})

test('warns when a start-list crew has no calculated result', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async p => p.endsWith('/start-list') ? {slots: [{crewId: 4}, {crewId: 5}]} : original(p))
  render(<Results readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  expect(await screen.findByText(/Standings use available calculated results/)).toBeInTheDocument()
})

test('shows successful results even when the start list request fails', async () => {
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async p => {if (p.endsWith('/start-list')) throw new Error('offline'); return original(p)})
  render(<Results readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  expect(await screen.findByText('1 — Crew A')).toBeInTheDocument()
  expect(await screen.findByRole('alert')).toBeInTheDocument()
})
test('only displays an applied tiebreak in overall results', async () => {
  window.location.hash = '#/results?competition=1&category=2'
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async p => p.endsWith('/standings') ? [{crewId: 4, crewName: 'Crew A', regionOrClub: 'Club', startNumber: 1, place: 1, totalPoints: 100, appliedTiebreakDisciplineId: 3, disciplines: [{disciplineId: 3, disciplineName: 'Sprint', place: 1, status: 'FINISHED', points: 100}]}] : original(p))
  render(<Results readOnly/>)
  expect(await screen.findByText('Applied tiebreak discipline: Sprint')).toBeInTheDocument()
  expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.endsWith('/tiebreak'))).toBe(false)
})

test('shows a safe localized API error on results refresh', async () => {
  const {ApiError} = await import('./api/client')
  vi.mocked(apiRequest).mockRejectedValue(new ApiError(503, {code: 'database.unavailable', parameters: {detail: 'secret'}}))
  render(<Results readOnly/>)
  expect(await screen.findByRole('alert')).toHaveTextContent('The server could not complete the request.')
  expect(screen.queryByText(/secret/)).not.toBeInTheDocument()
})

test('warns when a seeded parallel crew has no calculated result', async () => {
  window.location.hash = '#/results?competition=1&discipline=3&category=2'
  const original = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p.endsWith('/disciplines')) return [{id: 3, name: 'Parallel', type: 'PARALLEL_SPRINT', categoryIds: [2]}]
    if (p.endsWith('/bracket')) return {id: 8, heats: [{round: 1, first: {id: 4}, second: {id: 5}}]}
    return original(p)
  })
  render(<Results readOnly/>)
  expect(await screen.findByText(/Standings use available calculated results/)).toBeInTheDocument()
})
