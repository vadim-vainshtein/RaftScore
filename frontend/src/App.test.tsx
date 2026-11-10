import {act, cleanup, fireEvent, render, screen, within} from '@testing-library/react'
import {afterEach, beforeEach, expect, test, vi} from 'vitest'
import App from './App'

let desktop = false
let listeners: Set<(event: MediaQueryListEvent) => void>

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
beforeEach(() => {
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

test('navigates between section placeholders and responds to browser history', () => {
  render(<App/>)
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
  fireEvent.click(screen.getByRole('button', {name: 'Open navigation'}))
  const dialog = screen.getByRole('presentation')
  fireEvent.click(within(dialog).getByRole('link', {name: 'Category directory'}))
  expect(await screen.findByRole('heading', {name: 'Category directory'})).toBeInTheDocument()
  expect(screen.getByRole('button', {name: 'Open navigation'})).toHaveAttribute('aria-expanded', 'false')
})

test('shows an unknown route without selecting a section', () => {
  window.history.replaceState(null, '', '/#/unknown')
  render(<App/>)
  expect(screen.getByRole('heading', {name: 'Page not found'})).toBeInTheDocument()
})



test('closes the mobile drawer when entering desktop and keeps it closed on return', async () => {
  render(<App/>)
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
