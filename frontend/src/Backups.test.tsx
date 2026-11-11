import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import Backups from './Backups'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
const backup = {id: '00000000-0000-0000-0000-000000000001', createdAt: '2026-01-01T10:00:00Z', sizeBytes: 1234, formatVersion: '2', schemaVersion: '3'}
beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockImplementation(async path => path === '/api/backups' ? [backup] : {headerName: 'X-CSRF-TOKEN', token: 'token'})
  vi.mocked(csrfRequest).mockReset().mockResolvedValue(undefined)
})
afterEach(() => {cleanup(); vi.restoreAllMocks()})
async function confirmRestore() {
  fireEvent.click(screen.getByRole('checkbox', {name: /I understand/}))
  fireEvent.change(screen.getByLabelText('Current password'), {target: {value: 'secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Restore database'}))
}
test('lists metadata, provides authenticated native download and creates a backup', async () => {
  render(<Backups/>)
  expect(await screen.findByText('1,234 bytes')).toBeInTheDocument()
  expect(screen.getByRole('link', {name: /Download backup from/})).toHaveAttribute('href', `/api/backups/${backup.id}/download`)
  expect(screen.getByText('2')).toBeInTheDocument(); expect(screen.getByText('3')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Create backup'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/backups', 'POST'))
  await waitFor(() => expect(vi.mocked(apiRequest).mock.calls.filter(([p]) => p === '/api/backups')).toHaveLength(2))
})
test('stored restore requires confirmation and clears password before awaiting response', async () => {
  let finish!: () => void
  vi.mocked(csrfRequest).mockImplementation(() => new Promise(resolve => {finish = () => resolve(undefined)}))
  const session = vi.fn(); window.addEventListener('session-check', session)
  render(<Backups/>); fireEvent.click(await screen.findByRole('button', {name: /Restore backup from/}))
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText(/Stored backup created:/)).toBeInTheDocument()
  expect(within(dialog).getByRole('button', {name: 'Restore database'})).toBeDisabled()
  await confirmRestore()
  expect(screen.getByLabelText('Current password')).toHaveValue('')
  expect(csrfRequest).toHaveBeenCalledWith('/api/backups/restore', 'POST', {id: backup.id, confirmed: true, password: 'secret'})
  finish(); await waitFor(() => expect(session).toHaveBeenCalledTimes(1))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  window.removeEventListener('session-check', session)
})
test('upload sends multipart with CSRF and preserves file after a failed restore', async () => {
  vi.mocked(apiRequest).mockImplementation(async (path, init) => {
    if (path === '/api/backups') return [backup]
    if (path === '/api/auth/csrf') return {headerName: 'X-CSRF-TOKEN', token: 'token'}
    if (path === '/api/backups/restore') {
      expect(init?.headers).toEqual({'X-CSRF-TOKEN': 'token'})
      const form = init?.body as FormData
      expect(form.get('file')).toBeInstanceOf(File); expect(form.get('confirmed')).toBe('true'); expect(form.get('password')).toBe('secret')
      throw new ApiError(403, {code: 'backup.reauthentication_failed', parameters: {}})
    }
    throw new Error('unexpected request')
  })
  render(<Backups/>); await screen.findByRole('button', {name: /Restore backup from/})
  const input = document.querySelector<HTMLInputElement>('input[type=file]')!
  fireEvent.change(input, {target: {files: [new File(['zip'], 'race.zip', {type: 'application/zip', lastModified: 1000})]}})
  expect(screen.getByText(/File modified:/)).toHaveTextContent('Backup creation time unavailable')
  await confirmRestore()
  expect(await screen.findByRole('alert')).toHaveTextContent('Password was not accepted')
  expect(screen.getByText('Selected file: race.zip')).toBeInTheDocument()
  expect(screen.getByLabelText('Current password')).toHaveValue('')
  fireEvent.change(screen.getByLabelText('Current password'), {target: {value: 'new secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
})

test('successful upload dispatches session check and cancel clears the password', async () => {
  const session = vi.fn(); window.addEventListener('session-check', session)
  vi.mocked(apiRequest).mockImplementation(async path => path === '/api/backups' ? [backup] : path === '/api/auth/csrf' ? {headerName: 'X-CSRF', token: 'token'} : undefined)
  render(<Backups/>); await screen.findByRole('button', {name: /Restore backup from/})
  const input = document.querySelector<HTMLInputElement>('input[type=file]')!
  const file = new File(['zip'], 'archive.zip', {type: 'application/zip', lastModified: 0})
  fireEvent.change(input, {target: {files: [file]}})
  expect(screen.getByText(/File modified:/)).toHaveTextContent('Unavailable')
  fireEvent.change(screen.getByLabelText('Current password'), {target: {value: 'secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Cancel'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  fireEvent.change(input, {target: {files: [file]}})
  expect(screen.getByLabelText('Current password')).toHaveValue('')
  await confirmRestore()
  await waitFor(() => expect(session).toHaveBeenCalledTimes(1))
  const call = vi.mocked(apiRequest).mock.calls.find(([path]) => path === '/api/backups/restore')!
  expect(call[1]?.headers).toEqual({'X-CSRF': 'token'})
  expect((call[1]?.body as FormData).get('file')).toBe(file)
  window.removeEventListener('session-check', session)
})
