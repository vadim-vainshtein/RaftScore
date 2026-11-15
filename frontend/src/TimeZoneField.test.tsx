import {cleanup, fireEvent, render, screen, within} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import TimeZoneField from './TimeZoneField'
import './i18n'

afterEach(cleanup)
test('searches and selects a zone with Enter without submitting the form', () => {
  const change = vi.fn(), submit = vi.fn(event => event.preventDefault())
  render(<form onSubmit={submit}><TimeZoneField id="zone" value="UTC" date="2026-07-01" onChange={change}/><button type="submit">Save</button></form>)
  const input = screen.getByRole('combobox', {name: 'Time zone (IANA)'})
  expect(input).toHaveAttribute('id', 'zone')
  fireEvent.focus(input)
  fireEvent.change(input, {target: {value: 'Almaty'}})
  expect(within(screen.getByRole('listbox')).getByRole('option', {name: /Almaty/})).toBeInTheDocument()
  fireEvent.keyDown(input, {key: 'ArrowDown'})
  fireEvent.keyDown(input, {key: 'Enter'})
  expect(change).toHaveBeenCalledWith('Asia/Almaty')
  expect(submit).not.toHaveBeenCalled()
})
test('renders a legacy zone unavailable to Intl', () => {
  render(<TimeZoneField value="Legacy/Zone" date="2026-07-01" onChange={vi.fn()}/>)
  expect(screen.getByRole('combobox', {name: 'Time zone (IANA)'})).toHaveValue('Legacy/Zone')
})
