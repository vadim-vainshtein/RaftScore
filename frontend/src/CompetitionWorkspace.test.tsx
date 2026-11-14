import {cleanup, fireEvent, render, screen, waitFor} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import CompetitionWorkspace from './CompetitionWorkspace'
import {apiRequest, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.restoreAllMocks(); window.location.hash = ''})
test('completion preserves a refused dirty draft and blocks while a save is pending', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  let finishSave: (value: {version: number}) => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(() => new Promise(resolve => {finishSave = resolve as (value: {version: number}) => void}))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(true).mockReturnValueOnce(false)
  render(<CompetitionWorkspace path="/workspace/1/result-grid?discipline=3" readOnly={false}/> )
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  const input = await screen.findByLabelText('Time A')
  fireEvent.change(input, {target: {value: '1:14.25'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).not.toHaveBeenCalled()
  expect(input).toHaveValue('1:14.25')
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(1))
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).toHaveBeenCalledTimes(1)
  finishSave({version: 0})
  await waitFor(() => expect(screen.getByRole('button', {name: 'Save'})).toBeDisabled())
})

test('keeps the draft visible and disables edits while completion is pending or fails', async () => {
  window.location.hash = '#/workspace/1/result-grid?discipline=3'
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'SPRINT', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/start-list')) return {slots: [{crewId: 4, crewName: 'A', startNumber: 1, position: 1}]}
    if (path.endsWith('/attempt-grid')) return {attempts: [], standings: []}
    throw new Error(path)
  })
  let fail: (reason: Error) => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(() => new Promise((_, reject) => {fail = reject}))
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompetitionWorkspace path="/workspace/1/result-grid?discipline=3" readOnly={false}/> )
  fireEvent.mouseDown(await screen.findByLabelText('Category'))
  fireEvent.click(await screen.findByRole('option', {name: 'R4'}))
  const input = await screen.findByLabelText('Time A')
  fireEvent.change(input, {target: {value: '1:14.25'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  await waitFor(() => expect(input).toBeDisabled())
  expect(window.dispatchEvent(new Event('category-navigation', {cancelable: true}))).toBe(false)
  fail(new Error('conflict'))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(input).toBeEnabled()
  expect(input).toHaveValue('1:14.25')
  expect(screen.getByRole('button', {name: 'Complete competition'})).toBeEnabled()
})

test('guards edited sprint heats and pending saves before completing', async () => {
  window.location.hash = '#/workspace/1/parallel-sprint?discipline=3&category=2'
  let saved = false
  const heat = {id: 9, version: 0, round: 1, number: 1, first: {id: 4, name: 'A', startNumber: 1}, second: {id: 5, name: 'B', startNumber: 2}, firstStatus: 'FINISHED', secondStatus: 'FINISHED', firstTimeCentiseconds: 100, secondTimeCentiseconds: 200, winnerSlot: 1, winnerOverridden: false}
  vi.mocked(apiRequest).mockImplementation(async path => {
    if (path === '/api/competitions/1') return {id: 1, title: 'Race', version: 0, status: 'STARTED'}
    if (path.endsWith('/disciplines')) return [{id: 3, name: 'Sprint', type: 'PARALLEL_SPRINT', categoryIds: [2]}]
    if (path.endsWith('/categories')) return [{id: 2, displayName: 'R4'}]
    if (path.endsWith('/bracket')) return {id: 8, heats: [{...heat, ...(saved ? {version: 1, firstTimeCentiseconds: 300} : {})}]}
    throw new Error(path)
  })
  let finishSave: () => void = () => {}
  vi.mocked(csrfRequest).mockImplementation(path => path.endsWith('/status') ? Promise.resolve({id: 1, title: 'Race', version: 1, status: 'COMPLETED'}) : new Promise(resolve => {finishSave = () => {saved = true; resolve({})}}))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(true).mockReturnValueOnce(false)
  render(<CompetitionWorkspace path="/workspace/1/parallel-sprint?discipline=3&category=2" readOnly={false}/> )
  const input = await screen.findByLabelText('Time 1')
  fireEvent.change(input, {target: {value: '0:03.00'}})
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).not.toHaveBeenCalled()
  expect(input).toHaveValue('0:03.00')
  fireEvent.click(screen.getByRole('button', {name: 'Save heat'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(1))
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  expect(csrfRequest).toHaveBeenCalledTimes(1)
  finishSave()
  await waitFor(() => expect(screen.getByRole('button', {name: 'Save heat'})).toBeEnabled())
  confirm.mockClear()
  fireEvent.click(screen.getByRole('button', {name: 'Complete competition'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledTimes(2))
  expect(confirm).toHaveBeenCalledTimes(1)
})
