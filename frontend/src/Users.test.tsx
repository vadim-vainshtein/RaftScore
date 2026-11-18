import {cleanup, fireEvent, render, screen, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Users from './Users'
import ChangePassword from './ChangePassword'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockResolvedValue([{id: 1, username: 'admin', role: 'FULL_ACCESS', enabled: true}, {id: 2, username: 'viewer', role: 'READ_ONLY', enabled: true}])
  vi.mocked(csrfRequest).mockReset()
})
afterEach(cleanup)
test('lists both roles and permits management only for viewers', async () => {
  render(<Users/>)
  const admin = await screen.findByRole('region', {name: 'admin'})
  const viewer = screen.getByRole('region', {name: 'viewer'})
  expect(admin).toHaveTextContent('Full access')
  expect(within(admin).queryByRole('button', {name: 'Block access'})).not.toBeInTheDocument()
  expect(within(admin).queryByLabelText('New password')).not.toBeInTheDocument()
  expect(viewer).toHaveTextContent('Read only')
  expect(within(viewer).getByRole('button', {name: 'Block access'})).toBeInTheDocument()
})
test.each([
  [new ApiError(400, undefined), 'Check the entered values.'],
  [new ApiError(403, undefined), 'Access denied.'],
  [new Error('offline'), 'Connection failed. Please try again.'],
  [new ApiError(500, undefined), 'The server could not complete the request. Please try again.'],
  [new ApiError(409, undefined), 'The data changed. Refresh and try again.'],
])('shows a specific user administration error', async (failure, message) => {
  vi.mocked(csrfRequest).mockRejectedValue(failure)
  render(<Users/>)
  await screen.findByRole('region', {name: 'viewer'})
  fireEvent.change(screen.getByRole('textbox', {name: 'Username'}), {target: {value: 'new'}})
  fireEvent.change(screen.getByLabelText(/Initial password/), {target: {value: 'password'}})
  fireEvent.click(screen.getByRole('button', {name: 'Create viewer'}))
  expect(await screen.findByRole('alert')).toHaveTextContent(message)
})
test.each([
  [new ApiError(401, {code: 'auth.current_password_invalid', parameters: {}}), 'Current password was not accepted.'],
  [new ApiError(400, undefined), 'Check the entered values.'],
  [new ApiError(401, undefined), 'Access denied.'],
  [new ApiError(403, undefined), 'Access denied.'],
  [new Error('offline'), 'Connection failed. Please try again.'],
  [new ApiError(500, undefined), 'The server could not complete the request. Please try again.'],
])('shows a specific password change error', async (failure, message) => {
  vi.mocked(csrfRequest).mockRejectedValue(failure)
  render(<ChangePassword/>)
  fireEvent.click(screen.getByRole('button', {name: 'Change password'}))
  fireEvent.change(screen.getByLabelText('Current password'), {target: {value: 'old'}})
  fireEvent.change(screen.getByLabelText('New password'), {target: {value: 'new'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  expect(await screen.findByRole('alert')).toHaveTextContent(message)
})



