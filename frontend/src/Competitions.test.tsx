import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Competitions from './Competitions'
import {apiRequest} from './api/client'
import './i18n'
vi.mock('./api/client', () => ({apiRequest: vi.fn(), csrfRequest: vi.fn(), ApiError: class extends Error {status = 0}}))
beforeEach(() => {vi.mocked(apiRequest).mockReset().mockResolvedValue({content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'UPCOMING', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 2, totalElements: 21})})
afterEach(cleanup)
test('lists, searches and paginates competitions for read-only users', async () => {
  render(<Competitions readOnly/>)
  expect(await screen.findByText(/River race/)).toHaveTextContent('Categories: 2, crews: 4')
  expect(screen.queryByRole('button', {name: 'Create competition'})).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Search title'), {target: {value: 'River'}})
  await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenLastCalledWith(expect.stringContaining('search=River')))
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  await waitFor(() => expect(vi.mocked(apiRequest)).toHaveBeenLastCalledWith(expect.stringContaining('page=1')))
})
test('opens details for read-only users without editing controls', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? {id: 1, version: 0, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false} : {content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'UPCOMING', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  render(<Competitions readOnly/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Open'}))
  expect(await screen.findByRole('heading', {name: 'River race'})).toBeInTheDocument()
  expect(screen.queryByRole('button', {name: 'Edit'})).not.toBeInTheDocument()
})

test('opens the latest details directly for editing from the list', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/competitions/1' ? {id: 1, version: 7, title: 'Latest', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', timeZone: 'UTC', description: '', admissionConfirmationRequired: false} : {content: [{id: 1, title: 'River race', startDate: '2026-04-01', endDate: '2026-04-02', location: 'River', state: 'UPCOMING', categoryCount: 2, crewCount: 4}], page: 0, totalPages: 1, totalElements: 1})
  render(<Competitions readOnly={false}/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
  expect(await screen.findByLabelText(/Title/)).toHaveValue('Latest')
  expect(apiRequest).toHaveBeenCalledWith('/api/competitions/1')
})
