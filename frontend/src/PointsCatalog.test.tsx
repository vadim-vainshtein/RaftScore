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
