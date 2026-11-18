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
  expect(await screen.findByRole('link', {name: 'River race'})).toHaveAttribute('href', '#/workspace/1')
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
  link.addEventListener('click', event => event.preventDefault(), {once: true})
  expect(fireEvent.click(link)).toBe(false)
  expect(confirm).toHaveBeenCalledTimes(2)
  confirm.mockRestore()
})
test.each(['save', 'cancel'])('returns to the workspace after %s from direct editing', async action => {
  const details = {id: 1, version: 7, title: 'Race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : path.endsWith('/categories') ? [{id: 2, displayName: 'R1', crewSize: 1, version: 1, seatLabels: ['Bow']}] : {content: [], page: 0, totalPages: 0, totalElements: 0})
  vi.mocked(csrfRequest).mockResolvedValue(details)
  window.location.hash = '#/competitions?edit=1&fromWorkspace=1&discipline=3&category=2'
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByLabelText(/Title/)).toHaveValue('Race')
  expect(await screen.findByRole('button', {name: 'Edit category'})).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: action === 'save' ? 'Save' : 'Cancel'}))
  await waitFor(() => expect(window.location.hash).toBe('#/workspace/1?discipline=3&category=2'))
  if (action === 'save') expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1', 'PUT', expect.objectContaining({version: 7, title: 'Race'}))
})
test('does not redirect after saving if the user navigated away', async () => {
  const details = {id: 1, version: 7, title: 'Race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : path.endsWith('/categories') ? [] : {content: [], page: 0, totalPages: 0, totalElements: 0})
  let finishSave!: (value: unknown) => void
  vi.mocked(csrfRequest).mockReturnValue(new Promise(resolve => {finishSave = resolve}))
  window.location.hash = '#/competitions?edit=1&fromWorkspace=1'
  render(<Competitions readOnly={false}/>)
  await screen.findByLabelText(/Title/)
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  window.location.hash = '#/competitions'
  finishSave(details)
  await waitFor(() => expect(screen.queryByLabelText(/Title/)).not.toBeInTheDocument())
  expect(window.location.hash).toBe('#/competitions')
})


test('adds and removes categories without losing competition draft', async () => {
  const details = {id: 1, version: 0, title: 'Race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  const category = {id: 2, displayName: 'R4 / Open', crewSize: 4, version: 0, seatLabels: []}
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : path.endsWith('/categories') ? [] : path.startsWith('/api/category-templates') ? {content: [{id: 3, displayName: 'R4 / Open', archived: false}], totalPages: 1} : {content: [], totalPages: 0})
  vi.mocked(csrfRequest).mockResolvedValue(category)
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  window.location.hash = '#/competitions?edit=1&fromWorkspace=1'
  render(<Competitions readOnly={false}/> )
  fireEvent.change(await screen.findByLabelText(/Title/), {target: {value: 'Draft title'}})
  fireEvent.mouseDown(await screen.findByLabelText('Category template'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4 / Open'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add category'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/categories', 'POST', {templateId: 3}))
  fireEvent.click(await screen.findByRole('button', {name: 'Remove category R4 / Open'}))
  expect(window.confirm).toHaveBeenCalledWith('Remove category R4 / Open? This change will be saved immediately.')
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/categories/2', 'DELETE', {version: 0}))
  expect(screen.getByLabelText(/Title/)).toHaveValue('Draft title')
  vi.mocked(window.confirm).mockRestore()
})


test('canceling competition settings keeps an immediately saved category', async () => {
  const details = {id: 1, version: 0, title: 'Race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false, status: 'CREATED'}
  const category = {id: 2, displayName: 'R4 / Open', crewSize: 4, version: 0, seatLabels: []}
  let savedCategories: typeof category[] = []
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? details : path.endsWith('/categories') ? savedCategories : path.startsWith('/api/category-templates') ? {content: [{id: 3, displayName: category.displayName, archived: false}], totalPages: 1} : {content: [], totalPages: 0})
  vi.mocked(csrfRequest).mockImplementation(async (path, method) => {if (path.endsWith('/categories') && method === 'POST') {savedCategories = [category]; return category} throw new Error('Unexpected request')})
  window.location.hash = '#/competitions?edit=1'
  const view = render(<Competitions readOnly={false}/>)
  expect(await screen.findByText('Category list changes are saved immediately.')).toBeInTheDocument()
  fireEvent.change(await screen.findByLabelText(/Title/), {target: {value: 'Unsaved title'}})
  fireEvent.mouseDown(await screen.findByLabelText('Category template'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4 / Open'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add category'}))
  await screen.findByRole('button', {name: 'Remove category R4 / Open'})
  expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/categories', 'POST', {templateId: 3})
  fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
  expect(screen.queryByLabelText(/Title/)).not.toBeInTheDocument()
  expect(csrfRequest).not.toHaveBeenCalledWith('/api/competitions/1', 'PUT', expect.anything())
  view.unmount()
  window.location.hash = '#/competitions?edit=1'
  render(<Competitions readOnly={false}/>)
  expect(await screen.findByRole('button', {name: 'Remove category R4 / Open'})).toBeInTheDocument()
  expect(screen.getByLabelText(/Title/)).toHaveValue('Race')
})

test.each([{readOnly: true, status: 'CREATED'}, {readOnly: false, status: 'COMPLETED'}])('workspace edit respects access for $status with readOnly=$readOnly', async ({readOnly, status}) => {
  vi.mocked(apiRequest).mockResolvedValue({id: 1, status})
  window.location.hash = '#/workspace/1/edit?discipline=3&category=2'
  render(<Competitions readOnly={readOnly} workspaceEditId="1"/>)
  expect(await screen.findByRole('link', {name: 'Overview'})).toHaveAttribute('href', '#/workspace/1?discipline=3&category=2')
  expect(screen.queryByLabelText(/Title/)).not.toBeInTheDocument()
  expect(apiRequest).toHaveBeenCalledTimes(1)
  expect(apiRequest).toHaveBeenCalledWith('/api/competitions/1')
})

test('workspace edit keeps a details load failure visible without loading the competition list', async () => {
  vi.mocked(apiRequest).mockRejectedValue(new Error('network'))
  window.location.hash = '#/workspace/1/edit?category=2'
  render(<Competitions readOnly={false} workspaceEditId="1"/>)
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(await screen.findByRole('link', {name: 'Overview'})).toHaveAttribute('href', '#/workspace/1?category=2')
  expect(apiRequest).toHaveBeenCalledTimes(1)
})
