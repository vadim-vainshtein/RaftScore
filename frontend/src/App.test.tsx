import {act, cleanup, fireEvent, render, screen, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import App from './App'
import {apiRequest, ApiError} from './api/client'

vi.mock('./api/client', async importOriginal => ({...(await importOriginal<typeof import('./api/client')>()), apiRequest: vi.fn()}))
import i18n from './i18n'
import {initialLocale} from './i18n/supported-locales'

let desktop = false
let listeners: Set<(event: MediaQueryListEvent) => void>

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
beforeEach(() => {
  vi.mocked(apiRequest).mockReset().mockResolvedValue({username: 'admin', role: 'FULL_ACCESS'})
  window.history.replaceState(null, '', '/#/')
  desktop = false
  listeners = new Set()
  vi.stubGlobal('matchMedia', (media: string) => {
    if (media.startsWith('@media')) throw new Error('matchMedia requires a media query without @media')
    return ({
    media, get matches() { return desktop },
    addEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
    })
  })
})

test('navigates between section placeholders and responds to browser history', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  expect(screen.getByRole('heading', {name: 'Competitions'})).toBeInTheDocument()
  const nav = screen.getByRole('navigation', {name: 'Main navigation'})
  fireEvent.click(within(nav).getByRole('link', {name: 'Users'}))
  expect(window.location.hash).toBe('#/users')
  expect(screen.getByRole('heading', {name: 'Users'})).toBeInTheDocument()
  const selected = within(nav).getByRole('link', {name: 'Users'})
  expect(selected).toHaveAttribute('aria-current', 'page')
  expect(selected.closest('li')).not.toBeNull()
  window.history.replaceState(null, '', '/#/backups')
  fireEvent(window, new HashChangeEvent('hashchange'))
  expect(screen.getByRole('heading', {name: 'Backups'})).toBeInTheDocument()
})

test('opens and closes the mobile navigation after selecting a section', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  fireEvent.click(screen.getByRole('button', {name: 'Open navigation'}))
  const dialog = screen.getByRole('presentation')
  fireEvent.click(within(dialog).getByRole('link', {name: 'Category directory'}))
  expect(await screen.findByRole('heading', {name: 'Category directory'})).toBeInTheDocument()
  expect(screen.getByRole('button', {name: 'Open navigation'})).toHaveAttribute('aria-expanded', 'false')
})

test('shows an unknown route without selecting a section', async () => {
  window.history.replaceState(null, '', '/#/unknown')
  render(<App/>)
  expect(await screen.findByRole('heading', {name: 'Page not found'})).toBeInTheDocument()
})



test('closes the mobile drawer when entering desktop and keeps it closed on return', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  fireEvent.click(screen.getByRole('button', {name: 'Open navigation'}))
  expect(screen.getByRole('dialog', {name: 'Main navigation'})).toBeInTheDocument()
  act(() => {
    desktop = true
    listeners.forEach(listener => listener({matches: true} as MediaQueryListEvent))
  })
  expect(await screen.findByRole('button', {name: 'Open navigation'})).toHaveAttribute('aria-expanded', 'false')
  act(() => {
    desktop = false
    listeners.forEach(listener => listener({matches: false} as MediaQueryListEvent))
  })
  expect(screen.queryByRole('dialog', {name: 'Main navigation'})).not.toBeInTheDocument()
})

test('switches language without navigation and persists the choice', async () => {
  localStorage.setItem('raftscore.language', 'ru')
  window.history.replaceState({marker: 42}, '', '/?lang=en&other=value#/users')
  expect(initialLocale()).toBe('en')
  await i18n.changeLanguage(initialLocale())
  render(<App/>)
  await screen.findByRole('heading', {name: 'Users'})
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Language'}))
  fireEvent.click(screen.getByRole('option', {name: 'Русский'}))
  expect(await screen.findByRole('heading', {name: 'Пользователи'})).toBeInTheDocument()
  expect(document.documentElement.lang).toBe('ru')
  expect(localStorage.getItem('raftscore.language')).toBe('ru')
  expect(window.location.hash).toBe('#/users')
  expect(window.location.search).toBe('?lang=ru&other=value')
  expect(window.history.state).toEqual({marker: 42})
  expect(initialLocale()).toBe('ru')
  expect(vi.mocked(apiRequest).mock.calls.filter(([path]) => path === '/api/auth/principal')).toHaveLength(1)
  await i18n.changeLanguage('en')
})


test('authenticates with CSRF, restores a route, and logs out', async () => {
  window.history.replaceState(null, '', '/#/competitions/12')
  vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError(401, undefined))
    .mockResolvedValueOnce({headerName: 'X-CSRF-TOKEN', token: 'token'}).mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({username: 'reader', role: 'READ_ONLY'})
    .mockResolvedValueOnce({headerName: 'X-CSRF-TOKEN', token: 'new-token'}).mockResolvedValueOnce(undefined)
  render(<App/>)
  fireEvent.change(await screen.findByLabelText(/Username/), {target: {value: 'reader'}})
  fireEvent.change(screen.getByLabelText(/Password/), {target: {value: 'secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))
  expect(await screen.findByRole('heading', {name: 'Competitions'})).toBeInTheDocument()
  expect(screen.getByText('Read only')).toBeInTheDocument()
  expect(window.location.hash).toBe('#/competitions/12')
  expect(apiRequest).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({method: 'POST', headers: expect.objectContaining({'X-CSRF-TOKEN': 'token'})}))
  fireEvent.click(screen.getByRole('button', {name: 'Sign out'}))
  expect(await screen.findByLabelText(/Password/)).toBeInTheDocument()
  expect(apiRequest).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({method: 'POST', headers: expect.objectContaining({'X-CSRF-TOKEN': 'new-token'})}))
})

test('shows a neutral login failure after session expiration', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError(401, undefined))
  act(() => window.dispatchEvent(new Event('session-check')))
  await screen.findByLabelText(/Username/)
  vi.mocked(apiRequest).mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'}).mockRejectedValueOnce(new ApiError(401, undefined))
  fireEvent.change(screen.getByLabelText(/Username/), {target: {value: 'anyone'}})
  fireEvent.change(screen.getByLabelText(/Password/), {target: {value: 'wrong'}})
  fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password.')
})


test('keeps a valid session after an ambiguous failure and discards stale confirmation after logout', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  let resolveCheck!: (value: unknown) => void
  vi.mocked(apiRequest).mockImplementationOnce(() => new Promise(resolve => { resolveCheck = resolve }))
    .mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'}).mockResolvedValueOnce(undefined)
  act(() => window.dispatchEvent(new Event('session-check')))
  fireEvent.click(screen.getByRole('button', {name: 'Sign out'}))
  await screen.findByLabelText(/Username/)
  await act(async () => resolveCheck({username: 'admin', role: 'FULL_ACCESS'}))
  expect(screen.getByLabelText(/Username/)).toBeInTheDocument()
})

test('retains a valid session after a confirmed principal and falls back from a restricted route after login', async () => {
  window.history.replaceState(null, '', '/#/users/12')
  render(<App/>)
  await screen.findByRole('heading', {name: 'Users'})
  act(() => window.dispatchEvent(new Event('session-check')))
  await act(async () => {})
  expect(screen.getByRole('heading', {name: 'Users'})).toBeInTheDocument()
  vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError(401, undefined))
  act(() => window.dispatchEvent(new Event('session-check')))
  await screen.findByLabelText(/Username/)
  vi.mocked(apiRequest).mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'}).mockResolvedValueOnce(undefined).mockResolvedValueOnce({username: 'reader', role: 'READ_ONLY'})
  fireEvent.change(screen.getByLabelText(/Username/), {target: {value: 'reader'}})
  fireEvent.change(screen.getByLabelText(/Password/), {target: {value: 'secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))
  await screen.findByRole('heading', {name: 'Competitions'})
  expect(window.location.hash).toBe('#/competitions')
})

test.each(['FULL_ACCESS', 'READ_ONLY'] as const)('shows the translated %s role separately from a long username', async role => {
  await i18n.changeLanguage('ru')
  vi.mocked(apiRequest).mockResolvedValueOnce({username: 'a'.repeat(200), role})
  render(<App/>)
  expect(await screen.findByText(role === 'FULL_ACCESS' ? 'Полный доступ' : 'Только чтение')).toBeInTheDocument()
  expect(screen.getByTitle('a'.repeat(200))).toBeInTheDocument()
  await i18n.changeLanguage('en')
})

test.each([401, 200])('rechecks the principal after logout failure (principal status %s)', async status => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  vi.mocked(apiRequest).mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'})
    .mockRejectedValueOnce(new ApiError(403, undefined))
  if (status === 401) vi.mocked(apiRequest).mockRejectedValueOnce(new ApiError(401, undefined))
  else vi.mocked(apiRequest).mockResolvedValueOnce({username: 'admin', role: 'FULL_ACCESS'})
  fireEvent.click(screen.getByRole('button', {name: 'Sign out'}))
  if (status === 401) expect(await screen.findByLabelText(/Username/)).toBeInTheDocument()
  else expect(await screen.findByRole('alert')).toHaveTextContent('Unable to connect')
})

test('ignores a delayed expiry confirmation after a new login', async () => {
  render(<App/>)
  await screen.findByRole('heading', {name: 'Competitions'})
  let rejectCheck!: (reason: unknown) => void
  vi.mocked(apiRequest).mockImplementationOnce(() => new Promise((_, reject) => { rejectCheck = reject }))
    .mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'}).mockResolvedValueOnce(undefined)
  act(() => window.dispatchEvent(new Event('session-check')))
  fireEvent.click(screen.getByRole('button', {name: 'Sign out'}))
  await screen.findByLabelText(/Username/)
  vi.mocked(apiRequest).mockResolvedValueOnce({headerName: 'X-CSRF', token: 'token'}).mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({username: 'new', role: 'FULL_ACCESS'})
  fireEvent.change(screen.getByLabelText(/Username/), {target: {value: 'new'}})
  fireEvent.change(screen.getByLabelText(/Password/), {target: {value: 'secret'}})
  fireEvent.click(screen.getByRole('button', {name: 'Sign in'}))
  await screen.findByText('new')
  await act(async () => rejectCheck(new ApiError(401, undefined)))
  expect(screen.getByText('new')).toBeInTheDocument()
})
