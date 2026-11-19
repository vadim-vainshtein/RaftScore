import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import CompetitionWorkspace from './CompetitionWorkspace'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.restoreAllMocks(); window.location.hash = ''})
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
  expect(await screen.findByRole('link', {name: 'Edit'})).toHaveAttribute('href', '#/workspace/1/edit?category=2')
})


test.each(['crews', 'disciplines', 'start-lists', 'results', 'result-grid', 'parallel-sprint'])('shows a compact heading on %s and keeps details on overview', async section => {
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'CREATED', startDate: '2026-01-01', location: 'River'}
    return []
  })
  const view = render(<CompetitionWorkspace path={`/workspace/1/${section}`} readOnly={false}/>)
  expect(await screen.findByRole('heading', {name: /Race — .*2026 — River/})).toBeInTheDocument()
  expect(screen.queryByText('Categories: 0 · Disciplines: 0')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Start competition'})).not.toBeInTheDocument()
  view.rerender(<CompetitionWorkspace path="/workspace/1" readOnly={false}/>)
  expect(await screen.findByRole('heading', {name: /^Race$/})).toBeInTheDocument()
  expect(await screen.findByText('Categories: 0 · Disciplines: 0')).toBeInTheDocument()
  expect(screen.getByRole('button', {name: 'Start competition'})).toBeInTheDocument()
})

test('carries only selected filters to settings', async () => {
  window.location.hash = '#/workspace/1?discipline=3&category=2&unused=ignored'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/start-list')) return {id: null, slots: [], locked: false}
    return []
  })
  render(<CompetitionWorkspace path="/workspace/1?discipline=3&category=2&unused=ignored" readOnly={false}/>)
  expect(await screen.findByRole('link', {name: 'Edit'})).toHaveAttribute('href', '#/workspace/1/edit?discipline=3&category=2')
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
  const view = render(<CompetitionWorkspace path="/workspace/1/crews?category=2" readOnly={false}/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Admit'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalled())
  view.rerender(<CompetitionWorkspace path="/workspace/1?category=2" readOnly={false}/>)
  expect(await screen.findByText('Entries: 1 · Eligible: 1')).toBeInTheDocument()
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
  const view = render(<CompetitionWorkspace path="/workspace/1/disciplines?editDisciplines=1" readOnly={false}/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Delete'}))
  finishDelete()
  await waitFor(() => expect(screen.queryByRole('button', {name: 'Delete'})).not.toBeInTheDocument())
  view.rerender(<CompetitionWorkspace path="/workspace/1" readOnly={false}/>)
  await waitFor(() => expect(screen.getByRole('button', {name: 'Start competition'})).toBeEnabled())
  fireEvent.click(screen.getByRole('button', {name: 'Start competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/status', 'PUT', {status: 'STARTED', version: 1}))
})

test('shows missing results and prevents completion', async () => {
  window.location.hash = '#/workspace/1'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/completion-issues')) return [{discipline: 'Sprint', category: 'Open', crew: 'River', code: 'missingAttempt', attempt: 1, round: null, heat: null}]
    return []
  })
  vi.mocked(csrfRequest).mockClear()
  render(<CompetitionWorkspace path="/workspace/1" readOnly={false}/> )
  fireEvent.click(await screen.findByRole('button', {name: 'Complete competition'}))
  expect(await screen.findByText(/Open — Sprint — River — Missing result for attempt 1/)).toBeInTheDocument()
  expect(screen.getByRole('dialog').querySelector('button.MuiButton-contained')).toBeDisabled()
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('refreshes missing results after completion is rejected', async () => {
  window.location.hash = '#/workspace/1'
  let issueRequests = 0
  let finishRefresh!: (value: unknown) => void
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/completion-issues')) {
      if (++issueRequests === 1) return []
      return new Promise(resolve => {finishRefresh = resolve})
    }
    return []
  })
  vi.mocked(csrfRequest).mockRejectedValue(new Error('409'))
  render(<CompetitionWorkspace path="/workspace/1" readOnly={false}/> )
  fireEvent.click(await screen.findByRole('button', {name: 'Complete competition'}))
  const dialog = await screen.findByRole('dialog')
  const complete = within(dialog).getByRole('button', {name: 'Complete competition'})
  await waitFor(() => expect(complete).toBeEnabled())
  fireEvent.click(complete)
  await waitFor(() => expect(issueRequests).toBe(2))
  expect(complete).toBeDisabled()
  finishRefresh([{discipline: 'Sprint', category: 'Open', crew: 'River', code: 'missingAttempt', attempt: 1, round: null, heat: null}])
  expect(await within(dialog).findByText(/Open — Sprint — River — Missing result for attempt 1/)).toBeInTheDocument()
  expect(complete).toBeDisabled()
})

test('keeps completion disabled if refreshing issues fails', async () => {
  window.location.hash = '#/workspace/1'
  let issueRequests = 0
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/completion-issues')) {
      if (++issueRequests === 1) return []
      throw new Error('refresh failed')
    }
    return []
  })
  vi.mocked(csrfRequest).mockRejectedValue(new Error('409'))
  render(<CompetitionWorkspace path="/workspace/1" readOnly={false}/> )
  fireEvent.click(await screen.findByRole('button', {name: 'Complete competition'}))
  const dialog = await screen.findByRole('dialog')
  const complete = within(dialog).getByRole('button', {name: 'Complete competition'})
  await waitFor(() => expect(complete).toBeEnabled())
  fireEvent.click(complete)
  await waitFor(() => expect(issueRequests).toBe(2))
  await waitFor(() => expect(complete).toBeDisabled())
})

test.each([
  ['noParticipatingCategories', 'No categories are entered in any discipline'],
  ['noEligibleCrews', 'No eligible crews in participating categories'],
])('explains why an empty competition cannot be completed: %s', async (code, message) => {
  window.location.hash = '#/workspace/1'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'STARTED'}
    if (path.endsWith('/completion-issues')) return [{discipline: null, category: null, crew: null, code, attempt: null, round: null, heat: null}]
    return []
  })
  render(<CompetitionWorkspace path="/workspace/1" readOnly={false}/> )
  fireEvent.click(await screen.findByRole('button', {name: 'Complete competition'}))
  expect(await screen.findByText(message)).toBeInTheDocument()
  expect(within(screen.getByRole('dialog')).getByRole('button', {name: 'Complete competition'})).toBeDisabled()
})

test.each([false, true])('completed workspace separates protocol authority from live result writes (readOnly=%s)', async readOnly => {
  window.location.hash = '#/workspace/1/results?category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 4, status: 'COMPLETED'}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1'}]
    if (path.endsWith('/disciplines') || path.endsWith('/protocols') || path.endsWith('/standings')) return []
    throw new Error(path)
  })
  render(<CompetitionWorkspace path="/workspace/1/results?category=2" readOnly={readOnly}/> )
  await screen.findByText('Issued protocols')
  if (readOnly) expect(screen.queryByRole('button', {name: 'Issue protocol'})).not.toBeInTheDocument()
  else expect(screen.getByRole('button', {name: 'Issue protocol'})).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Recalculate'})).not.toBeInTheDocument()
  expect(screen.queryByRole('link', {name: 'Edit result'})).not.toBeInTheDocument()
})
