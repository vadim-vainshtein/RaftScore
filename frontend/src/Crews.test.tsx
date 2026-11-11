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
  if (path.endsWith('/entries/4')) return detail
  return [detail]
}); vi.mocked(csrfRequest).mockReset().mockResolvedValue({}) })
afterEach(() => cleanup())
test('filters crews and submits the current roster, typed answers and version', async () => {
  render(<Crews/>); const competition = await screen.findByLabelText('Competition')
  fireEvent.mouseDown(competition); fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  await screen.findByText(/River/)
  fireEvent.change(screen.getByLabelText('Search crews'), {target: {value: 'missing'}})
  expect(screen.queryByText(/River/)).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Search crews'), {target: {value: ''}})
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  expect(await screen.findByLabelText(/Surname/)).toHaveValue('Smith')
  expect(within(screen.getByRole('group', {name: 'Member 1'})).getByLabelText(/Given name/)).toHaveValue('Sam')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/entries/4', 'PUT', expect.objectContaining({version: 3, roster: [{participantId: 9}], requirementAnswers: [{requirementId: 5, value: false}]})))
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
  fireEvent.change(screen.getByLabelText(/Crew name/), {target: {value: 'New'}})
  fireEvent.change(screen.getByLabelText(/Region or club/), {target: {value: 'Club'}})
  fireEvent.change(screen.getByLabelText(/Representative/), {target: {value: 'Coach'}})
  fireEvent.change(screen.getByLabelText(/Start number/), {target: {value: '8'}})
  expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled()
  fireEvent.submit(screen.getByRole('button', {name: 'Save'}).closest('form')!)
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('read-only view requests only public data and offers no mutations', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}]}
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', admissionConfirmationRequired: true}
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R1', crewSize: 1}]
    if (path.endsWith('/entries')) return [{id: 4, categoryId: 2, crewName: 'River', regionOrClub: 'Club', startNumber: 7}]
    throw new Error(`Unexpected request ${path}`)
  })
  render(<Crews readOnly/>)
  fireEvent.mouseDown(await screen.findByLabelText('Competition'))
  fireEvent.click(await screen.findByRole('option', {name: 'Race'}))
  expect(await screen.findByText(/River/)).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: /Create crew|Edit|Admit|Reject/})).not.toBeInTheDocument()
  expect(vi.mocked(apiRequest).mock.calls.map(([path]) => path)).not.toEqual(expect.arrayContaining(['/api/competitions/1/entries/private', '/api/competitions/1/entries/requirements']))
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
  expect(screen.getByLabelText(/Crew name/)).toHaveValue('')
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
