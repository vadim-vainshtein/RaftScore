import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import CompetitionWorkspace from './CompetitionWorkspace'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.restoreAllMocks(); window.location.hash = ''})
test('completion preserves a refused dirty draft and blocks while a save is pending', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  let finishSave: (value: {version: number}) => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(() => new Promise(resolve => {finishSave = resolve as (value: {version: number}) => void}))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(true).mockReturnValueOnce(false)
  render(<CompetitionWorkspace path="/workspace/1/result-grid?discipline=3" readOnly={false}/> )
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  const input = await screen.findByLabelText('Time A')
  fireEvent.change(input, {target: {value: '1:14.25'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(screen.getByRole('dialog')).toHaveTextContent('Unsaved rows: 1')
  fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(csrfRequest).not.toHaveBeenCalled()
  expect(input).toHaveValue('1:14.25')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(1))
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).toHaveBeenCalledTimes(1)
  finishSave({version: 0})
  await waitFor(() => expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled())
})

test('keeps the draft visible and disables edits while completion is pending or fails', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  let fail: (reason: Error) => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(() => new Promise((_, reject) => {fail = reject}))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompetitionWorkspace path="/workspace/1/result-grid?discipline=3" readOnly={false}/> )
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  const input = await screen.findByLabelText('Time A')
  fireEvent.change(input, {target: {value: '1:14.25'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  fireEvent.click(screen.getByRole('button', {name: 'Discard and complete'}))
  await waitFor(() => expect(input).toBeDisabled())
  expect(window.dispatchEvent(new Event('category-navigation', {cancelable: true}))).toBe(false)
  fail(new Error('conflict'))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(input).toBeEnabled()
  expect(input).toHaveValue('1:14.25')
  expect(screen.getByRole('button', {name: 'Discard and complete'})).toBeEnabled()
})

test('guards edited sprint heats and pending saves before completing', async () => {
  window.location.hash = '#/workspace/1/parallel-sprint?discipline=3&category=2'
  let saved = false
  const heat = {id: 9, version: 0, round: 1, number: 1, first: {id: 4, name: 'A', startNumber: 1}, second: {id: 5, name: 'B', startNumber: 2}, firstStatus: 'FINISHED', secondStatus: 'FINISHED', firstTimeCentiseconds: 100, secondTimeCentiseconds: 200, winnerSlot: 1, winnerOverridden: false}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/bracket')) return {id: 8, heats: [{...heat, ...(saved ? {version: 1, firstTimeCentiseconds: 300} : {})}]}
    throw new Error(path)
  })
  let finishSave: () => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(path => path.endsWith('/status') ? Promise.resolve({id: 1, title: 'Race', version: 1, status: 'COMPLETED'}) : new Promise(resolve => {finishSave = () => {saved = true; resolve({})}}))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(true).mockReturnValueOnce(false)
  render(<CompetitionWorkspace path="/workspace/1/parallel-sprint?discipline=3&category=2" readOnly={false}/> )
  fireEvent.click(await screen.findByText('Open heat 1 form'))
  const input = await screen.findByLabelText('Time Position 1 — A')
  fireEvent.change(input, {target: {value: '0:03.00'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(screen.getByRole('dialog')).toHaveTextContent('Unsaved rows: 1')
  fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(csrfRequest).not.toHaveBeenCalled()
  expect(input).toHaveValue('0:03.00')
  fireEvent.click(screen.getByRole('button', {name: 'Save heat'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(1))
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).toHaveBeenCalledTimes(1)
  finishSave()
  await waitFor(() => expect(screen.getByText(/Position 1: 1 — A.*0:03\.00/)).toBeInTheDocument())
  await waitFor(() => expect(screen.getByRole('button', {name: 'Save heat'})).toBeEnabled())
  confirm.mockClear()
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  const dialog = await screen.findByRole('dialog')
  fireEvent.click(within(dialog).getByRole('button', {name: 'Complete competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(2))
})

test('completes from settings in the shared dialog and opens protocol', async () => {
  window.location.hash = '#/workspace/1?discipline=3&category=2&complete=1&unused=ignored'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    return []
  })
  vi.mocked(csrfRequest).mockResolvedValue({id: 1, version: 5, status: 'COMPLETED'})
  render(<CompetitionWorkspace path="/workspace/1?discipline=3&category=2&complete=1&unused=ignored" readOnly={false}/>)
  expect(await screen.findByRole('dialog')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/status', 'PUT', {status: 'COMPLETED', version: 4}))
  await waitFor(() => expect(window.location.hash).toBe('#/workspace/1/results?discipline=3&category=2&unused=ignored'))
})

test('carries only selected filters to settings', async () => {
  window.location.hash = '#/workspace/1/start-lists?discipline=3&category=2&unused=ignored'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/start-list')) return {id: null, slots: [], locked: false}
    return []
  })
  render(<CompetitionWorkspace path="/workspace/1/start-lists?discipline=3&category=2&unused=ignored" readOnly={false}/>)
  expect(await screen.findByRole('link', {name: 'Edit'})).toHaveAttribute('href', '#/competitions?edit=1&fromWorkspace=1&discipline=3&category=2')
})

test('completion while protocol is open updates status and removes write actions', async () => {
  window.location.hash = '#/workspace/1/results?discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: []}
    return []
  })
  vi.mocked(csrfRequest).mockResolvedValue({id: 1, title: 'Race', version: 5, status: 'COMPLETED'})
  render(<CompetitionWorkspace path="/workspace/1/results?discipline=3&category=2" readOnly={false}/>)
  expect(await screen.findByRole('button', {name: 'Recalculate'})).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  fireEvent.click(screen.getByRole('dialog').querySelector('button:last-child')!)
  await waitFor(() => expect(screen.getByText('Completed')).toBeInTheDocument())
  expect(screen.queryByRole('button', {name: 'Recalculate'})).not.toBeInTheDocument()
  await waitFor(() => expect(screen.queryByRole('button', {name: 'Complete competition'})).not.toBeInTheDocument())
  expect(window.location.hash).toBe('#/workspace/1/results?discipline=3&category=2')
})

test.each([false, true])('completed workspace keeps admin roster access separate from writes (readOnly=%s)', async readOnly => {
  window.location.hash = '#/workspace/1/crews?category=2'
  const crew = {id: 4, categoryId: 2, crewName: 'River', regionOrClub: 'Club', startNumber: 7, roster: [{surname: 'Smith', givenName: 'Sam', patronymic: ''}], admissionDecision: true, eligible: true, version: 1}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'COMPLETED', admissionConfirmationRequired: true}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
    if (path.endsWith('/disciplines') || path.endsWith('/requirements')) return []
    if (path.endsWith('/entries/private')) return [crew]
    if (path.endsWith('/entries')) return [{id: 4, categoryId: 2, crewName: 'River', regionOrClub: 'Club', startNumber: 7}]
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockClear()
  render(<CompetitionWorkspace path="/workspace/1/crews?category=2" readOnly={readOnly}/>)
  expect(await screen.findByText(/7 — River/)).toBeInTheDocument()
  if (readOnly) {
    expect(screen.queryByText('Smith Sam')).not.toBeInTheDocument()
    expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.endsWith('/entries/private'))).toBe(false)
  } else {
    expect(screen.getByText('Smith Sam')).toBeInTheDocument()
    expect(screen.getByText('Admitted')).toBeInTheDocument()
    expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.endsWith('/entries/private'))).toBe(true)
  }
  expect(screen.queryByRole('button', {name: /Create crew|Edit|Admit|Reject/})).not.toBeInTheDocument()
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('refreshes readiness after a crew admission', async () => {
  window.location.hash = '#/workspace/1/crews?category=2'
  let admitted = false
  const crew = () => ({id: 4, categoryId: 2, crewName: 'River', regionOrClub: 'Club', startNumber: 7, roster: [], admissionDecision: admitted, eligible: admitted, version: 1})
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', status: 'CREATED', version: 0, admissionConfirmationRequired: true}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
    if (path.endsWith('/disciplines') || path.endsWith('/requirements')) return []
    if (path.endsWith('/entries/private')) return [crew()]
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockImplementation(async () => {admitted = true; return {}})
  render(<CompetitionWorkspace path="/workspace/1/crews?category=2" readOnly={false}/>)
  expect(await screen.findByText('Entries: 1 · Eligible: 0')).toBeInTheDocument()
  fireEvent.click(await screen.findByRole('button', {name: 'Admit'}))
  expect(await screen.findByText('Entries: 1 · Eligible: 1')).toBeInTheDocument()
})

test('refreshes readiness after saving a discipline', async () => {
  window.location.hash = '#/workspace/1/disciplines'
  let added = false
  const discipline = {id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', displayOrder: 0, categoryIds: [], pointsTableId: null, resultsEntered: false}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', status: 'CREATED', version: 0}
    if (path.endsWith('/categories') || path.endsWith('/points-tables') || path.endsWith('/entries/private')) return []
    if (path.endsWith('/disciplines')) return added ? [discipline] : []
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockImplementation(async () => {added = true; return {}})
  render(<CompetitionWorkspace path="/workspace/1/disciplines?editDisciplines=1" readOnly={false}/>)
  expect(await screen.findByText('Categories: 0 · Disciplines: 0')).toBeInTheDocument()
  fireEvent.click(await screen.findByRole('button', {name: 'Create discipline'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  expect(await screen.findByText('Categories: 0 · Disciplines: 1')).toBeInTheDocument()
})

test('refreshes readiness after generating a start list', async () => {
  window.location.hash = '#/workspace/1/start-lists?discipline=3&category=2'
  let generated = false
  const slot = {crewId: 4, crewName: 'River', startNumber: 7, position: 1, plannedTime: null}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', status: 'CREATED', version: 0, startDate: '2026-01-01', timeZone: 'UTC'}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1'}]
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/entries/private')) return [{id: 4, categoryId: 2, crewName: 'River', startNumber: 7, eligible: true}]
    if (path.endsWith('/start-list')) return {id: generated ? 1 : null, slots: generated ? [slot] : [], locked: false}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockImplementation(async () => {generated = true; return {id: 1, slots: [slot], locked: false}})
  render(<CompetitionWorkspace path="/workspace/1/start-lists?discipline=3&category=2" readOnly={false}/>)
  expect(await screen.findByText('Crews in selected start list: 0')).toBeInTheDocument()
  fireEvent.change(await screen.findByLabelText(/First start time \(UTC\)/), {target: {value: '10:00'}})
  fireEvent.click(await screen.findByRole('button', {name: 'Generate start list'}))
  expect(await screen.findByText('Crews in selected start list: 1')).toBeInTheDocument()
})

test('refreshes readiness after creating a crew', async () => {
  window.location.hash = '#/workspace/1/crews?category=2'
  let created = false
  const crew = {id: 4, categoryId: 2, crewName: 'Smith Sam', regionOrClub: 'Club', startNumber: 7, roster: [{surname: 'Smith', givenName: 'Sam', patronymic: ''}], admissionDecision: true, eligible: true, version: 0}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', status: 'CREATED', version: 0, admissionConfirmationRequired: false}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
    if (path.endsWith('/disciplines') || path.endsWith('/requirements')) return []
    if (path.endsWith('/entries/private')) return created ? [crew] : []
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockImplementation(async () => {created = true; return {}})
  render(<CompetitionWorkspace path="/workspace/1/crews?category=2" readOnly={false}/>)
  expect(await screen.findByText('Entries: 0 · Eligible: 0')).toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('combobox', {name: 'Category'})).toHaveTextContent('R1'))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  fireEvent.change(screen.getByLabelText(/Start number/), {target: {value: '7'}})
  fireEvent.change(screen.getByLabelText(/Surname/), {target: {value: 'Smith'}})
  fireEvent.change(screen.getByLabelText(/Given name/), {target: {value: 'Sam'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  expect(await screen.findByText('Entries: 1 · Eligible: 1')).toBeInTheDocument()
})

test('discarding a draft when clearing category leaves no unsaved rows at completion', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {id: null, slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}], locked: false}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    if (path.endsWith('/entries/private')) return []
    throw new Error(path)
  })
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompetitionWorkspace path="/workspace/1/result-grid?discipline=3&category=2" readOnly={false}/>)
  fireEvent.change(await screen.findByLabelText('Time A'), {target: {value: '1:14.25'}})
  fireEvent.mouseDown(screen.getByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: '—'}))
  expect(confirm).toHaveBeenCalledTimes(1)
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(screen.getByRole('dialog')).not.toHaveTextContent('Unsaved rows')
  expect(screen.queryByRole('button', {name: 'Discard and complete'})).not.toBeInTheDocument()
})

test('discipline edit link opens composition editing while overview keeps competition settings', async () => {
  window.location.hash = '#/workspace/1/disciplines?category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'CREATED'}
    if (path.endsWith('/points-tables') || path.endsWith('/entries/private') || path.endsWith('/categories') || path.endsWith('/disciplines')) return []
    throw new Error(path)
  })
  const view = render(<CompetitionWorkspace path="/workspace/1/disciplines?category=2" readOnly={false}/>)
  const edit = await screen.findByRole('link', {name: 'Edit'})
  expect(edit).toHaveAttribute('href', '#/workspace/1/disciplines?category=2&editDisciplines=1')
  expect(screen.queryByRole('button', {name: 'Create discipline'})).not.toBeInTheDocument()
  view.rerender(<CompetitionWorkspace path="/workspace/1/disciplines?category=2&editDisciplines=1" readOnly={false}/>)
  expect(await screen.findByRole('button', {name: 'Create discipline'})).toBeInTheDocument()
  view.rerender(<CompetitionWorkspace path="/workspace/1" readOnly={false}/>)
  expect(await screen.findByRole('link', {name: 'Edit'})).toHaveAttribute('href', '#/competitions?edit=1&fromWorkspace=1&category=2')
})

test('refreshes the competition version after deleting its tiebreak before starting', async () => {
  window.location.hash = '#/workspace/1/disciplines?editDisciplines=1'
  let deleted = false
  const discipline = {id: 3, name: 'Sprint', type: 'SPRINT', status: 'CREATED', displayOrder: 0, categoryIds: [], pointsTableId: null, version: 0, resultsEntered: false}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', status: 'CREATED', version: deleted ? 1 : 0}
    if (path.endsWith('/categories') || path.endsWith('/points-tables') || path.endsWith('/entries/private') || path === '/api/points-catalog') return []
    if (path.endsWith('/disciplines')) return deleted ? [] : [discipline]
    throw new Error(path)
  })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  let finishDelete: () => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(async (path, method) => {
    if (method === 'DELETE') {await new Promise<void>(resolve => {finishDelete = resolve}); deleted = true; return undefined}
    if (path.endsWith('/status')) return {id: 1, title: 'Race', status: 'STARTED', version: 2}
    throw new Error(path)
  })
  render(<CompetitionWorkspace path="/workspace/1/disciplines?editDisciplines=1" readOnly={false}/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Delete'}))
  expect(screen.getByRole('button', {name: 'Start competition'})).toBeDisabled()
  finishDelete()
  await waitFor(() => expect(screen.queryByRole('button', {name: 'Delete'})).not.toBeInTheDocument())
  await waitFor(() => expect(screen.getByRole('button', {name: 'Start competition'})).toBeEnabled())
  fireEvent.click(screen.getByRole('button', {name: 'Start competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/status', 'PUT', {status: 'STARTED', version: 1}))
})
