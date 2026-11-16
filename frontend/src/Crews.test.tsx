import {act, cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Crews from './Crews'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const detail = {id: 4, categoryId: 2, crewName: 'River', regionOrClub: 'Club', representative: 'Coach', startNumber: 7, version: 3, roster: [{participantId: 9, surname: 'Smith', givenName: 'Sam', patronymic: ''}], requirementAnswers: [{requirementId: 5, value: false}], admissionDecision: null, eligible: true}
beforeEach(() => { vi.mocked(apiRequest).mockReset().mockImplementation(async path => {
  if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}]}
  if (path === '/api/competitions/1') return {id: 1, title: 'Race', admissionConfirmationRequired: true}
  if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
  if (path.endsWith('/requirements')) return [{id: 5, name: 'Consent', valueType: 'YES_NO', required: true}]
  if (path.includes('/api/crew-search')) return [{...detail, competitionId: 1, competitionTitle: 'Race', crewSize: 1, categoryName: 'R1', registeredAt: '2026-01-01T00:00:00Z'}]
  if (path.endsWith('/entries/4')) return detail
  return [detail]
}); vi.mocked(csrfRequest).mockReset().mockResolvedValue({}) })
afterEach(() => cleanup())
test('submits the current roster, typed answers and version', async () => {
  render(<Crews/>); const competition = await screen.findByLabelText('Competition')
  fireEvent.mouseDown(competition); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  await screen.findByText(/River/)
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  expect(await screen.findByLabelText(/Surname/)).toHaveValue('Smith')
  expect(within(screen.getByRole('group', {name: 'Member 1'})).getByLabelText(/Given name/)).toHaveValue('Sam')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries/4', 'PUT', expect.objectContaining({version: 3, roster: [{participantId: 9}], requirementAnswers: [{requirementId: 5, value: false}]})))
})

test('preserves an existing single participant representative on save', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/4')
    ? Promise.resolve({...detail, representative: 'Smith Sam', representativePosition: 1}) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
  await screen.findByLabelText(/Surname/)
  fireEvent.change(screen.getByLabelText(/Region or club/), {target: {value: 'New club'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries/4', 'PUT',
    expect.objectContaining({representative: 'Smith Sam', representativePosition: 1, regionOrClub: 'New club'})))
})

test('updates the linked representative name when a single participant is renamed', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/4')
    ? Promise.resolve({...detail, representative: 'Smith Sam', representativePosition: 1}) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
  fireEvent.change(await screen.findByLabelText(/Surname/), {target: {value: 'Jones'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries/4', 'PUT',
    expect.objectContaining({crewName: 'Jones Sam', representative: 'Jones Sam', representativePosition: 1})))
})

test('uses the saved entry for the next number while the list refresh is pending', async () => {
  let resolveRefresh!: (value: unknown) => void
  let loads = 0
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => {
    if (path.endsWith('/entries/private') && ++loads > 1) return new Promise(resolve => {resolveRefresh = resolve})
    return previous(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue({...detail, id: 5, startNumber: 1, crewName: 'Doe Jane', roster: [{participantId: 11, surname: 'Doe', givenName: 'Jane', patronymic: ''}]})
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  fireEvent.change(screen.getByLabelText(/Surname/), {target: {value: 'Doe'}})
  fireEvent.change(screen.getByLabelText(/Given name/), {target: {value: 'Jane'}})
  fireEvent.mouseDown(screen.getByRole('combobox', {name: /Consent/}))
  fireEvent.click(screen.getByRole('option', {name: 'Yes'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(loads).toBe(2))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  expect(screen.getByLabelText(/Start number/)).toHaveValue(2)
  await act(async () => resolveRefresh([detail, {...detail, id: 5, startNumber: 1}]))
})

test('saves a selected participant without a patronymic', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/private')
    ? Promise.resolve([{...detail, categoryId: 3, roster: [{participantId: 9, surname: 'Smith', givenName: 'Sam', patronymic: null}]}]) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  fireEvent.mouseDown(screen.getByLabelText('Existing participant'))
  fireEvent.click(screen.getByRole('option', {name: /Smith Sam/}))
  fireEvent.mouseDown(screen.getByRole('combobox', {name: /Consent/}))
  fireEvent.click(screen.getByRole('option', {name: 'Yes'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries', 'POST',
    expect.objectContaining({crewName: 'Smith Sam', roster: [{participantId: 9}]})))
})

test('selects participants by ID when names match', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}]}
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', admissionConfirmationRequired: false}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
    if (path.endsWith('/requirements')) return []
    return [detail, {...detail, id: 5, crewName: 'Rapid', roster: [{...detail.roster[0], participantId: 10}]}]
  })
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  fireEvent.mouseDown(screen.getByLabelText('Existing participant'))
  fireEvent.click(screen.getAllByRole('option', {name: /Smith Sam/})[1])
  expect(screen.getByRole('status')).toHaveTextContent('Rapid')
  fireEvent.change(screen.getByLabelText(/Region or club/), {target: {value: 'Club'}})
  fireEvent.change(screen.getByLabelText(/Start number/), {target: {value: '8'}})
  expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled()
  fireEvent.submit(screen.getByRole('button', {name: 'Save'}).closest('form')!)
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('ignores an edit response after a newer create intent', async () => {
  let resolveDetail!: (value: typeof detail) => void
  const pending = new Promise<typeof detail>(resolve => { resolveDetail = resolve })
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/4') ? pending : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  await act(async () => { resolveDetail(detail); await pending })
  expect(screen.getByLabelText(/Surname/)).toHaveValue('')
})

test('localizes known API errors and never displays server details', async () => {
  vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError(409, {code: 'crew_entry.start_number_duplicate', parameters: {detail: 'secret'}}))
  render(<Crews/>)
  expect(await screen.findByRole('alert')).toHaveTextContent('This start number is already in use.')
  expect(screen.queryByText(/secret/)).not.toBeInTheDocument()
  const {default: i18n} = await import('./i18n')
  await act(async () => { await i18n.changeLanguage('ru') })
  expect(screen.getByRole('alert')).toHaveTextContent('Этот стартовый номер уже используется.')
  await act(async () => { await i18n.changeLanguage('en') })
})

test('uses the selected roster participant as representative', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/categories') ? Promise.resolve([{id: 2, displayName: 'R1', crewSize: 2}]) : path.endsWith('/entries/4') ? Promise.resolve({...detail, roster: [...detail.roster, {surname: 'Other', givenName: 'Person', patronymic: ''}]}) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  await screen.findByLabelText('Representative from roster')
  fireEvent.mouseDown(screen.getByLabelText('Representative from roster'))
  fireEvent.click(screen.getByRole('option', {name: /1\. Smith Sam/}))
  fireEvent.change(screen.getAllByLabelText(/Surname/)[0], {target: {value: 'Jones'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries/4', 'PUT', expect.objectContaining({representative: 'Jones Sam', representativePosition: 1})))
})

test('shows registration dates in the browser time zone', async () => {
  const resolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions
  const zone = vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (this: Intl.DateTimeFormat) {
    return {...resolvedOptions.call(this), timeZone: 'Asia/Qyzylorda'}
  })
  try {
    const previous = vi.mocked(apiRequest).getMockImplementation()!
    vi.mocked(apiRequest).mockImplementation(path => path.includes('/crew-search?')
      ? Promise.resolve([{...detail, competitionId: 1, competitionTitle: 'Race', crewSize: 1, registeredAt: '2026-11-16T21:44:00Z'}]) : previous(path))
    render(<Crews/>)
    fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
    fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
    fireEvent.focus(screen.getByRole('combobox', {name: 'Select crew in category'}))
    expect(await screen.findByRole('option', {name: /River.*Race.*11\/17\/2026/})).toBeInTheDocument()
  } finally {zone.mockRestore()}
})
test('filters category history locally and copies an earlier registration', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  const old = {...detail, id: 90, competitionId: 9, categoryId: 20, competitionTitle: 'Old race', crewSize: 1, registeredAt: '2025-01-01T00:00:00Z'}
  vi.mocked(apiRequest).mockImplementation(path => path.includes('/crew-search?') ? Promise.resolve([old, {...old, id: 92, competitionTitle: 'Older race', crewSize: 2}, {...old, id: 91, crewName: 'Rapid', registeredAt: null}]) : path === '/api/competitions/9/entries/90' ? Promise.resolve({...detail, id: 90, roster: [{...detail.roster[0], participantId: 99}]}) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  const picker = screen.getByRole('combobox', {name: 'Select crew in category'})
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/crew-search?categoryId=2'))
  fireEvent.focus(picker)
  expect(await screen.findByRole('option', {name: /Rapid.*Registration date unknown/})).toBeInTheDocument()
  expect(screen.getByRole('option', {name: /River.*Old race/})).toBeInTheDocument()
  fireEvent.change(picker, {target: {value: 'riv'}})
  expect(await screen.findByRole('option', {name: /River.*Old race/})).toBeInTheDocument()
  expect(screen.queryByRole('option', {name: /Rapid/})).not.toBeInTheDocument()
  expect(screen.getByRole('option', {name: /River.*Older race/})).toHaveAttribute('aria-disabled', 'true')
  expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path.includes('/crew-search'))).toHaveLength(1)
  fireEvent.click(screen.getByRole('option', {name: /River.*Old race/}))
  expect(await screen.findByLabelText(/Surname/)).toHaveValue('Smith')
  fireEvent.mouseDown(screen.getByRole('combobox', {name: /Consent/})); fireEvent.click(screen.getByRole('option', {name: 'Yes'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries', 'POST', expect.objectContaining({categoryId: 2})))
})

test('preserves a typed new crew name in the creation form', async () => {
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/categories') ? Promise.resolve([{id: 2, displayName: 'R1', crewSize: 2}]) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.change(screen.getByRole('combobox', {name: 'Select crew in category'}), {target: {value: 'New team'}})
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  expect(screen.getByLabelText(/Crew name/)).toHaveValue('New team')
})

test('read-only history uses the public endpoint and offers no mutations', async () => {
  render(<Crews readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/crew-search/public?categoryId=2'))
  fireEvent.change(screen.getByRole('combobox', {name: 'Select crew in category'}), {target: {value: 'Riv'}})
  expect(await screen.findByRole('option', {name: /River.*Race/})).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: /Create crew|Edit|Admit|Reject/})).not.toBeInTheDocument()
})

test('waits for current entries before allowing a history selection', async () => {
  let resolveEntries!: (value: unknown) => void
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  const old = {...detail, id: 90, competitionId: 9, categoryId: 20, competitionTitle: 'Old race', crewSize: 1}
  vi.mocked(apiRequest).mockImplementation(path => {
    if (path.endsWith('/entries/private')) return new Promise(resolve => {resolveEntries = resolve})
    if (path.includes('/crew-search?')) return Promise.resolve([old])
    if (path === '/api/competitions/9/entries/90') return Promise.resolve({...detail, id: 90, roster: [{...detail.roster[0], participantId: 99}]})
    return previous(path)
  })
  window.location.hash = '#/workspace/1/crews?category=2'
  try {
    render(<Crews fixedCompetition="1"/>)
    const picker = await screen.findByRole('combobox', {name: 'Select crew in category'})
    expect(picker).toBeDisabled()
    await act(async () => {resolveEntries([{...detail, startNumber: 1}]); await Promise.resolve()})
    await waitFor(() => expect(picker).toBeEnabled())
    fireEvent.focus(picker)
    fireEvent.click(await screen.findByRole('option', {name: /River.*Old race/}))
    expect(await screen.findByLabelText(/Start number/)).toHaveValue(2)
  } finally {window.location.hash = ''}
})

test('clears an accepted draft before waiting for a history detail response', async () => {
  let resolveDetail!: (value: unknown) => void
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => {
    if (path.includes('/crew-search?')) return Promise.resolve([{...detail, id: 90, competitionId: 9, categoryId: 20, competitionTitle: 'Old race', crewSize: 1}])
    if (path === '/api/competitions/9/entries/90') return new Promise(resolve => {resolveDetail = resolve})
    return previous(path)
  })
  try {
    render(<Crews/>)
    fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
    fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
    fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
    fireEvent.change(await screen.findByLabelText(/Surname/), {target: {value: 'Edited'}})
    fireEvent.focus(screen.getByRole('combobox', {name: 'Select crew in category'}))
    fireEvent.click(await screen.findByRole('option', {name: /River.*Old race/}))
    expect(confirm).toHaveBeenCalledOnce()
    expect(screen.queryByLabelText(/Surname/)).not.toBeInTheDocument()
    await act(async () => {resolveDetail({...detail, id: 90}); await Promise.resolve()})
    expect(await screen.findByLabelText(/Surname/)).toHaveValue('Smith')
  } finally {confirm.mockRestore()}
})

test('category changes invalidate a pending selection', async () => {
  let resolveDetail!: (value: typeof detail) => void
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/4') ? new Promise(resolve => { resolveDetail = resolve }) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  const picker = screen.getByRole('combobox', {name: 'Select crew in category'})
  fireEvent.change(picker, {target: {value: 'River'}})
  fireEvent.click(await screen.findByRole('option', {name: /River.*Race/}))
  fireEvent.mouseDown(screen.getByLabelText('Category')); fireEvent.click(screen.getByRole('option', {name: 'All'}))
  await act(async () => {resolveDetail(detail); await Promise.resolve()})
  expect(screen.queryByLabelText(/Surname/)).not.toBeInTheDocument()
})

test('clearing the picker invalidates a pending selection', async () => {
  let resolveDetail!: (value: typeof detail) => void
  const previous = vi.mocked(apiRequest).getMockImplementation()!
  vi.mocked(apiRequest).mockImplementation(path => path.endsWith('/entries/4') ? new Promise(resolve => {resolveDetail = resolve}) : previous(path))
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  const picker = screen.getByRole('combobox', {name: 'Select crew in category'})
  fireEvent.change(picker, {target: {value: 'River'}}); fireEvent.click(await screen.findByRole('option', {name: /River.*Race/}))
  fireEvent.change(picker, {target: {value: ''}})
  await act(async () => {resolveDetail(detail); await Promise.resolve()})
  expect(screen.queryByLabelText(/Surname/)).not.toBeInTheDocument()
})

test('retains a dirty draft when changing category or crew is declined', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  try {
    const previous = vi.mocked(apiRequest).getMockImplementation()!
    vi.mocked(apiRequest).mockImplementation(path => path.includes('/crew-search?') ? Promise.resolve([{...detail, competitionId: 1, competitionTitle: 'Race', crewSize: 1}, {...detail, id: 5, crewName: 'Rapid', competitionId: 1, competitionTitle: 'Race', crewSize: 1}]) : previous(path))
    render(<Crews/>)
    fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
    fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
    fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
    fireEvent.change(await screen.findByLabelText(/Surname/), {target: {value: 'Edited'}})
    fireEvent.mouseDown(screen.getByLabelText('Category')); fireEvent.click(screen.getByRole('option', {name: 'All'}))
    const picker = screen.getByRole('combobox', {name: 'Select crew in category'})
    fireEvent.change(picker, {target: {value: 'Rapid'}})
    fireEvent.click(await screen.findByRole('option', {name: /Rapid/}))
    expect(screen.getByLabelText(/Surname/)).toHaveValue('Edited')
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(apiRequest).not.toHaveBeenCalledWith('/api/competitions/1/entries/5')
  } finally {confirm.mockRestore()}
})

test('preserves the typed full name for a single participant', async () => {
  render(<Crews/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.change(screen.getByRole('combobox', {name: 'Select crew in category'}), {target: {value: 'Doe Jane Ann'}})
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  expect(screen.getByLabelText(/Surname/)).toHaveValue('Doe')
  expect(screen.getByLabelText(/Given name/)).toHaveValue('Jane')
  expect(screen.getByLabelText(/Patronymic/)).toHaveValue('Ann')
})

test('single entry uses the member name and proposes the first free category number', async () => {
  render(<Crews/>); fireEvent.mouseDown(await screen.findByLabelText('Competition')); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  fireEvent.mouseDown(await screen.findByLabelText('Category')); fireEvent.click(await screen.findByRole('option', {name: 'R1'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create crew'}))
  expect(screen.queryByLabelText(/Crew name/)).not.toBeInTheDocument()
  expect(screen.queryByLabelText(/Representative/)).not.toBeInTheDocument()
  expect(screen.getByLabelText(/Start number/)).toHaveValue(1)
  fireEvent.change(screen.getByLabelText(/Surname/), {target: {value: 'Doe'}})
  fireEvent.change(screen.getByLabelText(/Given name/), {target: {value: 'Jane'}})
  fireEvent.mouseDown(screen.getByRole('combobox', {name: /Consent/})); fireEvent.click(screen.getByRole('option', {name: 'Yes'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries', 'POST', expect.objectContaining({crewName: 'Doe Jane', startNumber: 1})))
})
