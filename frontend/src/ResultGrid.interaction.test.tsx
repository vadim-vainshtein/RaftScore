import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import ResultGrid from './ResultGrid'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.resetAllMocks(); window.location.hash = ''})
test('saves a versioned row with Ctrl+S and advances focus', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3'
  const result = {crewId: 4, attemptNumber: 1, version: 7, durationCentiseconds: 100, startInstant: null, finishInstant: null, penaltyCentiseconds: 0, status: 'FINISHED', gates: []}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}, {crewId: 5, crewName: 'B', startNumber: 2, position: 2}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [result], standings: [{crewId: 4, place: 1, totalCentiseconds: 100, status: 'FINISHED'}]}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue(result)
  render(<ResultGrid/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  fireEvent.click(await screen.findByText('Table entry'))
  const input = await screen.findByLabelText('Time A')
  expect(screen.getByText('Entered 1 of 2')).toBeInTheDocument()
  fireEvent.change(input, {target: {value: '1:14.25'}})
  input.focus()
  fireEvent.keyDown(input, {key: 's', ctrlKey: true})
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/3/categories/2/crews/4/attempts/1', 'PUT', expect.objectContaining({version: 7, durationCentiseconds: 7425})))
  await waitFor(() => expect(screen.getByLabelText('Time B')).toHaveFocus())
})
test('slalom requires explicit gate scores and clears DNS values', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 2, attemptCount: 1}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue({version: 1})
  render(<ResultGrid/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  fireEvent.click(await screen.findByText('Table entry'))
  fireEvent.change(await screen.findByLabelText('Time A'), {target: {value: '74.25'}})
  fireEvent.change(screen.getByLabelText('Gate 1 A'), {target: {value: '0'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  expect(csrfRequest).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Gate 2 A'), {target: {value: '5'}})
  expect(screen.getByLabelText('Penalty A')).toHaveTextContent('5 s')
  fireEvent.change(screen.getByLabelText('Status A'), {target: {value: 'DNS'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.any(String), 'PUT', expect.objectContaining({status: 'DNS', durationCentiseconds: null, penaltyCentiseconds: 0, gates: []})))
})
test('retains a failed draft and preserves select arrow keys', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 2, attemptCount: 1}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: [{crewId: 4, place: 2, totalCentiseconds: 7425, status: 'FINISHED'}]}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockRejectedValue(new Error('failed'))
  render(<ResultGrid/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  fireEvent.click(await screen.findByText('Table entry'))
  const time = await screen.findByLabelText('Time A')
  fireEvent.change(time, {target: {value: '1:14.25'}})
  const gate = screen.getByLabelText('Gate 1 A')
  fireEvent.change(gate, {target: {value: '0'}})
  fireEvent.change(screen.getByLabelText('Gate 2 A'), {target: {value: '5'}})
  const arrow = new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true, cancelable: true})
  gate.dispatchEvent(arrow)
  expect(arrow.defaultPrevented).toBe(false)
  expect(screen.getByText('01:14.25')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await screen.findByRole('alert')
  expect(time).toHaveValue('1:14.25')
  expect(gate).toHaveValue('0')
})
test('resets gate page when changing discipline', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Long slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 25, attemptCount: 1}, {id: 4, name: 'Short slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 2, attemptCount: 1}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  render(<ResultGrid/>)
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  fireEvent.click(await screen.findByText('Table entry'))
  await screen.findByLabelText('Gate 1 A')
  fireEvent.change(screen.getByLabelText('Gate page (20 per page)'), {target: {value: '2'}})
  expect(screen.getByLabelText('Gate 21 A')).toBeInTheDocument()
  expect(screen.getByLabelText('Gate 23 A').closest('td')).toHaveAttribute('data-label', 'Gate 23')
  const row = screen.getByLabelText('Time A').closest('tr')!
  for (const label of ['Attempt', 'Time', 'Penalty', 'Status', 'Attempt total', 'Best / total', 'Place']) expect(row.querySelector(`[data-label="${label}"]`)).toBeInTheDocument()
  fireEvent.mouseDown(screen.getByLabelText('Discipline'))
  fireEvent.click(await screen.findByRole('option', {name: 'Short slalom'}))
  fireEvent.mouseDown(screen.getByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  fireEvent.click(await screen.findByText('Table entry'))
  expect(await screen.findByLabelText('Gate 1 A')).toBeInTheDocument()
  expect(screen.getByLabelText('Gate page (20 per page)')).toHaveValue(1)
})

test('rejects a parallel discipline in a direct timed grid link', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=8&category=2'
  vi.mocked(apiRequest).mockImplementation(async p => {
    if (p === '/api/competitions/1') return {id: 1, title: 'Race'}
    if (p.endsWith('/disciplines')) return [{id: 8, name: 'Parallel', type: 'PARALLEL_SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (p.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    throw new Error(p)
  })
  render(<ResultGrid fixedCompetition="1"/>)
  await waitFor(() => expect(window.location.hash).toBe('#/workspace/1/result-grid?category=2'))
  expect(vi.mocked(apiRequest).mock.calls.some(([p]) => p.includes('/attempt-grid') || p.endsWith('/start-list'))).toBe(false)
})

test.each(['CREATED', 'COMPLETED'])('disables all result controls when discipline is %s', async status => {
  window.location.hash = '#/result-grid?competition=1&discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Slalom', type: 'SLALOM', status, categoryIds: [2], gateCount: 1, attemptCount: 1}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  render(<ResultGrid/>)
  expect(await screen.findByLabelText('Time A')).toBeDisabled()
  expect(screen.getByLabelText('Status A')).toBeDisabled()
  expect(screen.getByLabelText('Gate 1 A')).toBeDisabled()
  fireEvent.keyDown(screen.getByLabelText('Time A'), {key: 's', ctrlKey: true})
  expect(csrfRequest).not.toHaveBeenCalled()
})

test('judge entry previews penalties and opens the next missing crew after saving', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3&category=2&crew=4&attempt=1'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 1, attemptCount: 2}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'C', startNumber: 3, position: 1}, {crewId: 5, crewName: 'D', startNumber: 4, position: 2}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue({version: 1})
  render(<ResultGrid/>)
  const time = await screen.findByLabelText('Time')
  fireEvent.change(time, {target: {value: '01:35.00'}})
  fireEvent.click(screen.getByRole('button', {name: '50'}))
  expect(screen.getByText('Provisional total: 02:25.00')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Save and continue'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.stringContaining('/crews/4/attempts/1'), 'PUT', expect.objectContaining({durationCentiseconds: 9500, gates: [{number: 1, penaltySeconds: 50}]})))
  await waitFor(() => expect(screen.getByRole('heading', {name: '4 — D'})).toBeInTheDocument())
  expect(screen.getByLabelText('Time')).toHaveFocus()
  fireEvent.mouseDown(screen.getByLabelText('Status'))
  fireEvent.click(screen.getByRole('option', {name: 'Did not start'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save and continue'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.stringContaining('/crews/5/attempts/1'), 'PUT', expect.objectContaining({status: 'DNS', durationCentiseconds: null})))
})

test('switching from slalom attempt two to sprint loads and saves attempt one', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3&category=2&attempt=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Slalom', type: 'SLALOM', status: 'STARTED', categoryIds: [2], gateCount: 1, attemptCount: 2}, {id: 4, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 5, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: path.includes('/disciplines/4/') ? [{crewId: 5, attemptNumber: 1, version: 7, durationCentiseconds: 7400, startInstant: null, finishInstant: null, penaltyCentiseconds: 0, status: 'FINISHED', gates: []}] : [], standings: []}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue({version: 8})
  render(<ResultGrid/>)
  expect(await screen.findByLabelText('Attempt')).toHaveValue(2)
  fireEvent.mouseDown(screen.getByLabelText('Discipline'))
  fireEvent.click(await screen.findByRole('option', {name: 'Sprint'}))
  await waitFor(() => expect(screen.getByLabelText('Time')).toHaveValue('01:14.00'))
  fireEvent.change(screen.getByLabelText('Time'), {target: {value: '01:15.00'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save and continue'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/1/disciplines/4/categories/2/crews/5/attempts/1', 'PUT', expect.objectContaining({version: 7, durationCentiseconds: 7500})))
})

test('focuses the next crew status when its unsaved draft is DNS', async () => {
  window.location.hash = '#/result-grid?competition=1&discipline=3&category=2'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions?size=100') return {content: [{id: 1, title: 'Race'}], totalPages: 1}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', status: 'STARTED', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}, {crewId: 5, crewName: 'B', startNumber: 2, position: 2}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  vi.mocked(csrfRequest).mockResolvedValue({version: 1})
  render(<ResultGrid/>)
  fireEvent.click(await screen.findByRole('button', {name: /2 — B/}))
  fireEvent.mouseDown(screen.getByLabelText('Status'))
  fireEvent.click(screen.getByRole('option', {name: 'Did not start'}))
  fireEvent.click(screen.getByRole('button', {name: /1 — A/}))
  fireEvent.change(screen.getByLabelText('Time'), {target: {value: '01:35.00'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save and continue'}))
  await waitFor(() => expect(screen.getByRole('heading', {name: '2 — B'})).toBeInTheDocument())
  await waitFor(() => expect(screen.getByLabelText('Status')).toHaveFocus())
  expect(screen.getByLabelText('Time')).toBeDisabled()
})
