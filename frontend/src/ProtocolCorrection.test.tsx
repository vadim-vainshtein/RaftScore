import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import {apiRequest, csrfRequest} from './api/client'
import ProtocolCorrection from './ProtocolCorrection'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(() => {cleanup(); vi.resetAllMocks()})

test('allows the secretary to enter a missing start-list attempt', async () => {
  vi.mocked(apiRequest).mockImplementation(async path => path.endsWith('/attempt-grid') ? {attempts: []} : [{code: 'missingAttempt', disciplineId: 3, categoryId: 2, crewId: 8, crew: 'Crew', attempt: 1}])
  vi.mocked(csrfRequest).mockResolvedValue({})
  render(<ProtocolCorrection competition="1" category="2" disciplines={[{id: 3, name: 'Slalom', type: 'SLALOM', gateCount: 1}]} crews={[]} onSaved={() => {}}/>)
  fireEvent.click(screen.getByText('Correct a result'))
  fireEvent.change(screen.getByLabelText('Discipline'), {target: {value: '3'}})
  await screen.findByRole('option', {name: 'Crew — 1'})
  fireEvent.change(screen.getByLabelText('Attempt'), {target: {value: '8:1'}})
  fireEvent.change(screen.getByLabelText('Time, centiseconds'), {target: {value: '100'}})
  fireEvent.change(screen.getByLabelText('Reason'), {target: {value: 'Missing'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save correction'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.stringContaining('/crews/8/attempts/corrections/1'), 'PUT', expect.objectContaining({reason: 'Missing', result: expect.objectContaining({version: null, durationCentiseconds: 100, gates: [{number: 1, penaltySeconds: 0}]})})))
})

test('requires confirmation for a new judge decision and supports returning to automatic', async () => {
  vi.mocked(apiRequest).mockResolvedValue({id: 5, heats: [{id: 9, version: 2, round: 2, number: 2, first: {id: 8, name: 'A'}, second: {id: 9, name: 'B'}, firstResolved: true, secondResolved: true, firstStatus: 'FINISHED', secondStatus: 'FINISHED', firstTimeCentiseconds: 100, secondTimeCentiseconds: 110, winnerSlot: 1, winnerOverridden: true}]})
  vi.mocked(csrfRequest).mockResolvedValue({})
  render(<ProtocolCorrection competition="1" category="2" disciplines={[{id: 4, name: 'Parallel', type: 'PARALLEL_SPRINT'}]} crews={[]} onSaved={() => {}}/>)
  fireEvent.click(screen.getByText('Correct a result'))
  fireEvent.change(screen.getByLabelText('Discipline'), {target: {value: '4'}})
  await screen.findByRole('option', {name: '2 / 2: A — B'})
  fireEvent.change(screen.getByLabelText('Heat'), {target: {value: '9'}})
  fireEvent.change(screen.getByLabelText('Reason'), {target: {value: 'Judge'}})
  const save = screen.getByRole('button', {name: 'Save correction'})
  expect(save).toBeDisabled()
  const decision = screen.getByLabelText('Winner decision')
  fireEvent.change(decision, {target: {value: '9'}})
  expect(save).toBeDisabled()
  fireEvent.click(screen.getByLabelText('Confirm judge decision'))
  fireEvent.click(save)
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.stringContaining('/heats/9/correction'), 'PUT', expect.objectContaining({result: expect.objectContaining({winnerCrewId: 9, confirmed: true})})))
  fireEvent.change(screen.getByLabelText('Discipline'), {target: {value: '4'}})
  await waitFor(() => expect(within(screen.getByLabelText('Heat')).getByRole('option', {name: '2 / 2: A — B'})).toBeInTheDocument())
  fireEvent.change(screen.getByLabelText('Heat'), {target: {value: '9'}})
  fireEvent.change(screen.getByLabelText('Winner decision'), {target: {value: 'auto'}})
  fireEvent.change(screen.getByLabelText('Reason'), {target: {value: 'Automatic'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save correction'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenLastCalledWith(expect.any(String), 'PUT', expect.objectContaining({result: expect.objectContaining({winnerCrewId: null, confirmed: false})})))
})

test('offers an unrecorded heat but excludes byes and unresolved slots', async () => {
  vi.mocked(apiRequest).mockResolvedValue({id: 5, heats: [
    {id: 9, version: 0, round: 2, number: 1, first: {id: 8, name: 'A'}, second: {id: 9, name: 'B'}, firstResolved: true, secondResolved: true, firstStatus: null, secondStatus: null, firstTimeCentiseconds: null, secondTimeCentiseconds: null, winnerSlot: null, winnerOverridden: false},
    {id: 10, version: 0, round: 1, number: 1, first: {id: 8, name: 'A'}, second: null, firstResolved: true, secondResolved: true},
    {id: 11, version: 0, round: 2, number: 2, first: {id: 8, name: 'A'}, second: {id: 9, name: 'B'}, firstResolved: true, secondResolved: false},
  ]})
  vi.mocked(csrfRequest).mockResolvedValue({})
  render(<ProtocolCorrection competition="1" category="2" disciplines={[{id: 4, name: 'Parallel', type: 'PARALLEL_SPRINT'}]} crews={[]} onSaved={() => {}}/>)
  fireEvent.click(screen.getByText('Correct a result'))
  fireEvent.change(screen.getByLabelText('Discipline'), {target: {value: '4'}})
  await screen.findByRole('option', {name: '2 / 1: A — B'})
  expect(screen.queryByRole('option', {name: /1 \/ 1:/})).not.toBeInTheDocument()
  expect(screen.queryByRole('option', {name: /2 \/ 2:/})).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Heat'), {target: {value: '9'}})
  const times = screen.getAllByLabelText('Time, centiseconds')
  fireEvent.change(times[0], {target: {value: '100'}}); fireEvent.change(times[1], {target: {value: '110'}})
  fireEvent.change(screen.getByLabelText('Reason'), {target: {value: 'Missing heat'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save correction'}))
  await waitFor(() => expect(csrfRequest).toHaveBeenCalledWith(expect.stringContaining('/heats/9/correction'), 'PUT', expect.objectContaining({reason: 'Missing heat', result: expect.objectContaining({first: {status: 'FINISHED', timeCentiseconds: 100}, second: {status: 'FINISHED', timeCentiseconds: 110}})})))
})

test('explains that a played-bracket seeding dispute is handled outside the system', async () => {
  const {ApiError} = await import('./api/client')
  vi.mocked(apiRequest).mockImplementation(async path => path.endsWith('/attempt-grid') ? {attempts: [{crewId: 8, attemptNumber: 1, version: 0, durationCentiseconds: 100, startInstant: null, finishInstant: null, penaltyCentiseconds: 0, status: 'FINISHED', gates: []}]} : [])
  vi.mocked(csrfRequest).mockRejectedValue(new ApiError(409, {code: 'protocol.seeding_change', parameters: {}}))
  render(<ProtocolCorrection competition="1" category="2" disciplines={[{id: 3, name: 'Sprint', type: 'SPRINT'}]} crews={[{id: 8, name: 'Crew'}]} onSaved={() => {}}/>)
  fireEvent.click(screen.getByText('Correct a result'))
  fireEvent.change(screen.getByLabelText('Discipline'), {target: {value: '3'}})
  await screen.findByRole('option', {name: 'Crew — 1'})
  fireEvent.change(screen.getByLabelText('Attempt'), {target: {value: '8:1'}})
  fireEvent.change(screen.getByLabelText('Reason'), {target: {value: 'Correction'}})
  fireEvent.click(screen.getByRole('button', {name: 'Save correction'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Changes to the seeding of a bracket with recorded heats are handled outside the system.')
})
