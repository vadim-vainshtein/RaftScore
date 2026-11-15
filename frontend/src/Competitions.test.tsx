import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Competitions from './Competitions'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', () => ({apiRequest: vi.fn(), csrfRequest: vi.fn(), ApiError: class extends Error {status = 0}}))
beforeEach(() => {vi.mocked(apiRequest).mockReset().mockResolvedValue({content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'CREATED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 2, totalElements: 21})})
afterEach(() => {cleanup(); window.location.hash = ''})
test('lists, searches and paginates competitions for read-only users', async () => {
  render(<Competitions readOnly/>)
  const card = (await screen.findByRole('heading', {name: 'River race'})).closest('article')!
  expect(within(card).getByText('Categories: 2, crews: 4')).toBeInTheDocument()
  expect(within(card).getByText('River')).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Create competition'})).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Search title'), {target: {value: 'River'}})
  await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenLastCalledWith(expect.stringContaining('search=River')))
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenLastCalledWith(expect.stringContaining('page=1')))
})
test('opens details for read-only users without editing controls', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? {id: 1, version: 0, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'} : {content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'CREATED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  window.location.hash = '#/competitions?view=1'
render(<Competitions readOnly/>)
  expect(await screen.findByRole('heading', {name: 'River race'})).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
})

test('opens the latest details directly for editing from the list', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? {id: 1, version: 7, title: 'Latest', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'} : {content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'CREATED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  window.location.hash = '#/competitions?edit=1'
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByLabelText(/Title/)).toHaveValue('Latest')
  expect(apiRequest).toHaveBeenCalledWith('/api/competitions/1')
})

test('starts a created competition using its version', async () => {
  const details = {id: 1, version: 7, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : path.endsWith('/categories') ? [] : {content: [{...details, state: 'CREATED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  vi.mocked(csrfRequest).mockResolvedValue({...details, status: 'STARTED', version: 8})
  window.location.hash = '#/competitions?view=1'
render(<Competitions readOnly={false}/>);
  fireEvent.click(await screen.findByRole('button', {name: 'Start competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/status', 'PUT', {status: 'STARTED', version: 7}))
  expect(await screen.findByRole('link', {name: 'Competition workspace'})).toHaveAttribute('href', '#/workspace/1')
})

test('hides edit for completed competitions in the list and details', async () => {
  const details = {id: 1, version: 2, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'COMPLETED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : {content: [{...details, state: 'COMPLETED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  window.location.hash = '#/competitions?view=1'
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByRole('heading', {name: 'River race'})).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit category'})).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Back to list'}))
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
})
test('opens completed details instead of editing if status changed since listing', async () => {
  const details = {id: 1, version: 2, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'COMPLETED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : {content: [{...details, state: 'STARTED', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  window.location.hash = '#/competitions?edit=1'
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByRole('heading', {name: 'River race'})).toBeInTheDocument()
  expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
})

test('edits seat labels inside the selected competition category', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? {id: 1, version: 0, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'} : path.endsWith('/categories') ? [{id: 2, displayName: 'R1 / Open', crewSize: 1, version: 4, seatLabels: ['Bow']}] : {content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'CREATED', categoryCount: 1, crewCount: 0}], page: 0, totalPages: 1, totalElements: 1})
  vi.mocked(csrfRequest).mockResolvedValue({id: 2, displayName: 'R1 / Open', crewSize: 1, version: 5, seatLabels: ['Front']})
  window.location.hash = '#/competitions?view=1'
render(<Competitions readOnly={false}/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Edit category'}))
  const editor = screen.getByRole('group', {name: 'Edit category: R1 / Open'})
  fireEvent.change(within(editor).getByLabelText('Seat 1'), {target: {value: 'Front'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/categories/2/seat-labels', 'PUT', {version: 4, seatLabels: ['Front']}))
})

test.each(['CREATED', 'STARTED', 'COMPLETED'])('offers the workspace for %s', async state => {
  vi.mocked(apiRequest).mockResolvedValue({content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state, categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByRole('link', {name: 'Competition workspace'})).toHaveAttribute('href', '#/workspace/1')
})

test('routes completion from settings through the workspace dialog', async () => {
  window.location.hash = '#/competitions?view=1&discipline=3&category=2&unused=ignored'
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1'
    ? {id: 1, version: 2, title: 'Race', status: 'STARTED', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', admissionConfirmationRequired: false}
    : path.endsWith('/categories') ? [] : {content: [], totalPages: 0})
  vi.mocked(csrfRequest).mockClear()
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByRole('link', {name: 'Complete competition'})).toHaveAttribute('href', '#/workspace/1?discipline=3&category=2&complete=1')
  expect(screen.getByRole('link', {name: 'Competition workspace'})).toHaveAttribute('href', '#/workspace/1?discipline=3&category=2')
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('confirms discarding changed competition settings before opening discipline editing', async () => {
  const details = {id: 1, version: 7, title: 'Race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : {content: [], page: 0, totalPages: 0, totalElements: 0})
  window.location.hash = '#/competitions?edit=1'
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  render(<Competitions readOnly={false}/>)
  fireEvent.change(await screen.findByLabelText(/Title/), {target: {value: 'Changed'}})
  const link = screen.getByRole('link', {name: 'Disciplines'})
  expect(link).toHaveAttribute('href', '#/workspace/1/disciplines?editDisciplines=1')
  expect(fireEvent.click(link)).toBe(false)
  expect(screen.getByLabelText(/Title/)).toHaveValue('Changed')
  expect(confirm).toHaveBeenCalledWith('Discard unsaved competition changes?')
  expect(fireEvent.click(link)).toBe(true)
  confirm.mockRestore()
})
