import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Disciplines from './Disciplines'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const slalom = {id: 3, name: 'Slalom', type: 'SLALOM', status: 'STARTED', displayOrder: 1, categoryIds: [2], gateCount: 4, attemptCount: 2, qualificationDisciplineId: null, pointsTableId: null, version: 5, resultsEntered: false}
const load = (items: object[] = [slalom]) => vi.mocked(apiRequest).mockImplementation(async path => {
  if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race', state: 'CREATED'}], totalPages: 1}
  if (path.endsWith('/points-tables')) return [{id: 8, name: 'Standard'}]
  if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}, {id: 9, displayName: 'R6'}]
  return items
})
beforeEach(() => {vi.mocked(apiRequest).mockReset(); load(); vi.mocked(csrfRequest).mockReset().mockResolvedValue({})})
afterEach(() => {cleanup(); vi.restoreAllMocks()})
async function selectCompetition() { fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'})) }
test('edits persisted slalom settings with the current version', async () => {
  render(<Disciplines readOnly={false}/>); await selectCompetition()
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'})); fireEvent.change(screen.getByLabelText(/Gates/), {target: {value: '6'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3/configuration', 'PUT', expect.objectContaining({gateCount: 6, version: 5, categoryIds: [2]})))
})
test('read-only users see attempt counts without write controls', async () => {
  load([slalom, {...slalom, id: 4, name: 'Sprint', type: 'SPRINT', status: 'STARTED', attemptCount: null}, {...slalom, id: 5, name: 'Long race', type: 'LONG_RACE', status: 'STARTED', attemptCount: null}])
  render(<Disciplines readOnly/>); await selectCompetition()
  expect(await screen.findByText(/Slalom.*Attempts: 2/)).toBeInTheDocument()
  expect(screen.getByText(/Sprint.*Attempts: 1/)).toBeInTheDocument()
  expect(screen.getByText(/Long race.*Attempts: 1/)).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
})
test('assigns points with the existing versioned endpoint', async () => {
  render(<Disciplines readOnly={false}/>); await selectCompetition()
  fireEvent.mouseDown(await screen.findByLabelText('Points table')); fireEvent.click(await screen.findByRole('option', {name: 'Standard'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3', 'PUT', {displayOrder: 1, pointsTableId: 8, version: 5}))
})
test('requires confirmation before changing points after results exist', async () => {
  load([{...slalom, resultsEntered: true}]); const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  render(<Disciplines readOnly={false}/>); await selectCompetition()
  fireEvent.mouseDown(await screen.findByLabelText('Points table')); fireEvent.click(await screen.findByRole('option', {name: 'Standard'}))
  expect(confirm).toHaveBeenCalledWith('Changing the points table will recalculate existing results. Continue?'); expect(csrfRequest).not.toHaveBeenCalled()
  await waitFor(() => expect(screen.queryByRole('listbox')).not.toBeInTheDocument())
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Points table'})); fireEvent.click(await screen.findByRole('option', {name: 'Standard'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3', 'PUT', expect.objectContaining({pointsTableId: 8, version: 5})))
})
test('clears a qualification selection when categories make it incompatible', async () => {
  const sprint = {...slalom, id: 4, name: 'Qualifier', type: 'SPRINT', status: 'STARTED', attemptCount: null}
  const parallel = {...slalom, id: 5, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', gateCount: null, attemptCount: null, qualificationDisciplineId: 4}
  load([sprint, parallel]); render(<Disciplines readOnly={false}/>); await selectCompetition()
  const label = await screen.findByText(/Parallel —/)
  expect(label).not.toHaveTextContent('Parallel sprint')
  const row = label.parentElement!
  fireEvent.click(within(row).getByRole('button', {name: 'Edit'}))
  expect(screen.getByLabelText('Qualification discipline')).toHaveTextContent('Qualifier')
  fireEvent.click(screen.getByLabelText('R6'))
  expect(screen.getByLabelText('Qualification discipline')).toHaveTextContent('Automatic')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/5/configuration', 'PUT', expect.objectContaining({categoryIds: [2, 9], qualificationDisciplineId: null})))
})

test('contextual discipline links retain only compatible categories', async () => {
  window.location.hash = '#/workspace/1/disciplines?category=2'
  const parallel = {...slalom, id: 5, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [9]}
  load([slalom, parallel])
  render(<Disciplines fixedCompetition="1" readOnly={false} scoringAllowed/>)
  const timedRow = (await screen.findByText(/Slalom —/)).parentElement!
  expect(within(timedRow).getByRole('link', {name: 'Start lists'})).toHaveAttribute('href', '#/workspace/1/start-lists?discipline=3&category=2')
  expect(within(timedRow).getByRole('link', {name: 'Results'})).toHaveAttribute('href', '#/workspace/1/results?discipline=3&category=2')
  expect(within(timedRow).getByRole('link', {name: 'Result entry'})).toHaveAttribute('href', '#/workspace/1/result-grid?discipline=3&category=2')
  const parallelRow = screen.getByText(/Parallel —/).parentElement!
  expect(within(parallelRow).getByRole('link', {name: 'Parallel sprint'})).toHaveAttribute('href', '#/workspace/1/parallel-sprint?discipline=5')
  window.location.hash = ''
})

test('global discipline links retain their established routes', async () => {
  render(<Disciplines readOnly={false} scoringAllowed/>)
  await selectCompetition()
  const row = (await screen.findByText(/Slalom —/)).parentElement!
  expect(within(row).getByRole('link', {name: 'Start lists'})).toHaveAttribute('href', '#/start-lists?competition=1&discipline=3')
  expect(within(row).getByRole('link', {name: 'Results'})).toHaveAttribute('href', '#/results?competition=1&discipline=3')
  expect(within(row).getByRole('link', {name: 'Result entry'})).toHaveAttribute('href', '#/result-grid?competition=1&discipline=3')
})

test('starts a discipline with its current version and hides scoring until started', async () => {
  load([{...slalom, status: 'CREATED'}])
  render(<Disciplines readOnly={false}/>); await selectCompetition()
  expect(await screen.findByText(/Preparation/)).toBeInTheDocument()
  expect(screen.queryByRole('link', {name: 'Result entry'})).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Start discipline'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3/status', 'PUT', {status: 'STARTED', version: 5}))
})

test('confirms removal and sends the current discipline version', async () => {
  load([{...slalom, status: 'CREATED'}]); vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<Disciplines readOnly={false}/>); await selectCompetition()
  fireEvent.click(await screen.findByRole('button', {name: 'Delete'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3', 'DELETE', {version: 5}))
})

test('hides deletion after the competition starts', async () => {
  load([{...slalom, status: 'CREATED'}])
  render(<Disciplines readOnly={false} competitionStatus="STARTED"/>); await selectCompetition()
  expect(await screen.findByText(/Slalom/)).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Delete'})).not.toBeInTheDocument()
})
