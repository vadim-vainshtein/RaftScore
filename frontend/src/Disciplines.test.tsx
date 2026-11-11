import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Disciplines from './Disciplines'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const slalom = {id: 3, name: 'Slalom', type: 'SLALOM', displayOrder: 1, categoryIds: [2], gateCount: 4, attemptCount: 2, qualificationDisciplineId: null, pointsTableId: null, version: 5, resultsEntered: false}
const load = (items: object[] = [slalom]) => vi.mocked(apiRequest).mockImplementation(async path => {
  if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
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
  load([slalom, {...slalom, id: 4, name: 'Sprint', type: 'SPRINT', attemptCount: null}, {...slalom, id: 5, name: 'Long race', type: 'LONG_RACE', attemptCount: null}])
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
  const sprint = {...slalom, id: 4, name: 'Qualifier', type: 'SPRINT', attemptCount: null}
  const parallel = {...slalom, id: 5, name: 'Parallel', type: 'PARALLEL_SPRINT', gateCount: null, attemptCount: null, qualificationDisciplineId: 4}
  load([sprint, parallel]); render(<Disciplines readOnly={false}/>); await selectCompetition()
  const row = (await screen.findByText(/Parallel.*Parallel sprint/)).parentElement!
  fireEvent.click(within(row).getByRole('button', {name: 'Edit'}))
  expect(screen.getByLabelText('Qualification discipline')).toHaveTextContent('Qualifier')
  fireEvent.click(screen.getByLabelText('R6'))
  expect(screen.getByLabelText('Qualification discipline')).toHaveTextContent('Automatic')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/5/configuration', 'PUT', expect.objectContaining({categoryIds: [2, 9], qualificationDisciplineId: null})))
})
