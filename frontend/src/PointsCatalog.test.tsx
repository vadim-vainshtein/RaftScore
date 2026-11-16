import {cleanup, fireEvent, render, screen, within, waitFor} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import PointsCatalog from './PointsCatalog'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', () => ({apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.resetAllMocks()})
test('groups same discipline first and selects a reusable table', async () => {
  const rows = [{place: 1, points: 100}]
  vi.mocked(apiRequest).mockResolvedValue([{id: 2, name: 'Slalom schedule', disciplineType: 'SLALOM', version: 0, preset: false, rows}, {id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 1, preset: false, rows}])
  vi.mocked(csrfRequest).mockResolvedValue({id: 3, name: 'Copy', disciplineType: 'SPRINT', version: 0, preset: false, rows})
  const changed = vi.fn()
  render(<PointsCatalog disciplineType="SPRINT" onChange={changed}/> )
  await waitFor(() => expect(apiRequest).toHaveBeenCalled())
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Points table'}))
  const options = await screen.findAllByRole('option')
  expect(options.findIndex(x => x.textContent === 'Sprint schedule')).toBeLessThan(options.findIndex(x => x.textContent === 'Slalom schedule'))
  fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', {name: 'Sprint schedule'}))
  expect(changed).toHaveBeenCalledWith(expect.objectContaining({name: 'Sprint schedule', catalogId: 1}))
})

test('shows the old snapshot distinctly when another discipline updates the catalog', async () => {
  const old = {id: 1, name: 'Original', disciplineType: 'SPRINT', version: 0, preset: false, rows: [{place: 1, points: 100}]}
  vi.mocked(apiRequest).mockResolvedValue([old])
  const selected = {name: old.name, tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: old.rows, catalogId: 1, catalogVersion: 0, catalogType: 'SPRINT'}
  render(<PointsCatalog disciplineType="SPRINT" value={selected} onChange={vi.fn()}/>)
  await waitFor(() => expect(screen.getByRole('combobox', {name: 'Points table'})).toHaveTextContent('Original'))
  vi.mocked(apiRequest).mockResolvedValue([{...old, name: 'Updated', version: 1, rows: [{place: 1, points: 50}]}])
  window.dispatchEvent(new Event('points-catalog-changed'))
  await waitFor(() => expect(screen.getByRole('combobox', {name: 'Points table'})).toHaveTextContent('Original (saved snapshot)'))
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Points table'}))
  expect(await screen.findByRole('option', {name: 'Updated'})).toBeInTheDocument()
})

test('standalone catalog switches tabs, shows table details, and cancels edits', async () => {
  const rows = [{place: 1, points: 100}]
  vi.mocked(apiRequest).mockResolvedValue([{id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 1, preset: false, rows}, {id: 2, name: 'Slalom schedule', disciplineType: 'SLALOM', version: 0, preset: false, rows}])
  render(<PointsCatalog/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Sprint schedule'}))
  expect(screen.queryByRole('combobox', {name: 'Points table'})).not.toBeInTheDocument()
  expect(screen.getByRole('table')).toHaveTextContent('100')
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  fireEvent.change(screen.getByLabelText('Points table name'), {target: {value: 'Changed'}})
  expect(screen.getByRole('tab', {name: 'Slalom'})).toBeDisabled()
  fireEvent.click(screen.getByRole('button', {name: 'Cancel editing'}))
  expect(screen.getByRole('heading', {name: 'Sprint schedule'})).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Back'}))
  fireEvent.click(screen.getByRole('tab', {name: 'Slalom'}))
  expect(screen.getByRole('button', {name: 'Slalom schedule'})).toBeInTheDocument()
})

test('standalone save offers updating or creating a copy', async () => {
  const rows = [{place: 1, points: 100}]
  vi.mocked(apiRequest).mockResolvedValue([{id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 1, preset: false, rows}])
  vi.mocked(csrfRequest).mockResolvedValue({id: 2, name: 'Copy', disciplineType: 'SPRINT', version: 0, preset: false, rows})
  render(<PointsCatalog/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Sprint schedule'}))
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  fireEvent.change(screen.getByLabelText('Points table name'), {target: {value: 'Copy'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Create new table'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/points-catalog', 'POST', expect.objectContaining({name: 'Copy'})))
  expect(await screen.findByRole('heading', {name: 'Copy'})).toBeInTheDocument()
})

test('creates a table in the current tab and saves an edit to its destination tab', async () => {
  const rows = [{place: 1, points: 25}]
  vi.mocked(apiRequest).mockResolvedValue([])
  vi.mocked(csrfRequest).mockResolvedValueOnce({id: 4, name: 'New', disciplineType: 'SLALOM', version: 0, preset: false, rows}).mockResolvedValueOnce({id: 4, name: 'New', disciplineType: 'SPRINT', version: 1, preset: false, rows})
  render(<PointsCatalog/>)
  fireEvent.click(screen.getByRole('tab', {name: 'Slalom'}))
  fireEvent.click(screen.getByRole('button', {name: 'Create table'}))
  fireEvent.change(screen.getByLabelText('Points table name'), {target: {value: 'New'}})
  fireEvent.click(screen.getByRole('button', {name: 'Add row'}))
  fireEvent.change(screen.getByLabelText('Points'), {target: {value: '25'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/points-catalog', 'POST', expect.objectContaining({disciplineType: 'SLALOM'})))
  fireEvent.click(await screen.findByRole('button', {name: 'Edit'}))
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Type'}))
  fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', {name: 'Sprint'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Save changes'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenLastCalledWith('/api/points-catalog/4', 'PUT', expect.objectContaining({disciplineType: 'SPRINT', version: 0})))
  await waitFor(() => expect(screen.getByRole('tab', {name: 'Sprint'})).toHaveAttribute('aria-selected', 'true'))
  expect(within(screen.getByRole('tabpanel')).getByRole('table')).toHaveTextContent('25')
})

test('invalid existing edits show the validation error after closing the save dialog', async () => {
  vi.mocked(apiRequest).mockResolvedValue([{id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 1, preset: false, rows: [{place: 1, points: 100}]}])
  render(<PointsCatalog/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Sprint schedule'}))
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  fireEvent.change(screen.getByLabelText('Points table name'), {target: {value: ''}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Save changes'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(screen.getByRole('alert')).toHaveTextContent('Enter a name')
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('prevents edits while a save request is pending', async () => {
  vi.mocked(apiRequest).mockResolvedValue([{id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 1, preset: false, rows: [{place: 1, points: 100}]}])
  let resolveSave!: (value: unknown) => void
  vi.mocked(csrfRequest).mockReturnValue(new Promise(resolve => {resolveSave = resolve}))
  render(<PointsCatalog/>)
  fireEvent.click(await screen.findByRole('button', {name: 'Sprint schedule'}))
  fireEvent.click(screen.getByRole('button', {name: 'Edit'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', {name: 'Save changes'}))
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  expect(screen.getByLabelText('Points table name')).toBeDisabled()
  expect(screen.getByRole('combobox', {name: 'Type'})).toHaveAttribute('aria-disabled', 'true')
  expect(screen.getByLabelText('Place')).toBeDisabled()
  expect(screen.getByLabelText('Points')).toBeDisabled()
  expect(screen.getByRole('button', {name: 'Remove'})).toBeDisabled()
  expect(screen.getByRole('button', {name: 'Add row'})).toBeDisabled()
  resolveSave({id: 1, name: 'Sprint schedule', disciplineType: 'SPRINT', version: 2, preset: false, rows: [{place: 1, points: 100}]})
  expect(await screen.findByRole('heading', {name: 'Sprint schedule'})).toBeInTheDocument()
})
