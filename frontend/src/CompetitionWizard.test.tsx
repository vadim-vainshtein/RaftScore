import {cleanup, fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import {afterEach, expect, test, vi} from 'vitest'
import CompetitionWizard from './CompetitionWizard'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import './i18n'
vi.mock('./api/client', async original => ({...(await original<typeof import('./api/client')>()), apiRequest: vi.fn(), csrfRequest: vi.fn()}))
afterEach(cleanup)
test.each([false, true])('submits all five steps with explicit tiebreak=%s', async explicit => {
  vi.mocked(apiRequest).mockResolvedValue({content: [], totalPages: 0})
  vi.mocked(csrfRequest).mockResolvedValue({id: 1})
  const saved = vi.fn()
  render(<CompetitionWizard onCancel={vi.fn()} onSaved={saved}/>)
  fireEvent.change(screen.getByLabelText('Title'), {target: {value: 'River'}})
  fireEvent.change(screen.getByLabelText('Start date'), {target: {value: '2026-04-01'}})
  fireEvent.change(screen.getByLabelText('Location'), {target: {value: 'Bank'}})
  fireEvent.change(screen.getByLabelText('Time zone (IANA)'), {target: {value: 'UTC'}})
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add custom category'}))
  fireEvent.change(screen.getByLabelText('Class'), {target: {value: 'R4'}})
  fireEvent.change(screen.getByLabelText('Scoring group'), {target: {value: 'Open'}})
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add requirement'}))
  fireEvent.change(screen.getByLabelText('Requirement name'), {target: {value: 'Waiver'}})
  fireEvent.click(screen.getByRole('checkbox', {name: 'Required'}))
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add discipline'}))
  fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Sprint'}})
  fireEvent.click(screen.getByRole('checkbox', {name: 'R4 / Open'}))
  fireEvent.click(screen.getByRole('button', {name: 'Russian points preset'}))
  if (explicit) {
    fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Tiebreak discipline'}))
    fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', {name: 'Sprint'}))
  }
  fireEvent.click(screen.getByRole('button', {name: 'Add discipline'}))
  const names = screen.getAllByLabelText('Name')
  fireEvent.change(names[1], {target: {value: 'Slalom'}})
  fireEvent.mouseDown(screen.getAllByRole('combobox', {name: 'Type'})[1])
  fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', {name: 'Slalom'}))
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  expect(screen.getByText(`Tiebreak discipline: ${explicit ? 'Sprint' : 'Slalom'}`)).toBeInTheDocument()
  expect(screen.getByText(/Sprint · Sprint · R4 \/ Open · Order: 0/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  await waitFor(() => expect(saved).toHaveBeenCalled())
  expect(csrfRequest).toHaveBeenCalledWith('/api/competitions/setup', 'POST', expect.objectContaining({
    competition: expect.objectContaining({title: 'River', startDate: '2026-04-01', endDate: null, location: 'Bank', timeZone: 'UTC'}),
    categories: [{classLabel: 'R4', scoringGroupLabel: 'Open', crewSize: 1}],
    requirements: [{name: 'Waiver', valueType: 'YES_NO', required: true, displayOrder: 0}],
    disciplines: [expect.objectContaining({name: 'Sprint', pointsTable: expect.objectContaining({rows: expect.arrayContaining([{place: 1, points: 100}, {place: 20, points: 5}])})}), expect.objectContaining({name: 'Slalom', type: 'SLALOM', gateCount: 1, attemptCount: 2})],
    tiebreakDisciplineIndex: explicit ? 0 : 1,
  }))
}, 15000)

test('returns to and focuses an invalid field from a server error', async () => {
  vi.mocked(apiRequest).mockResolvedValue({content: [], totalPages: 0})
  vi.mocked(csrfRequest).mockRejectedValueOnce(new ApiError(400, {code: 'request.validation_failed', parameters: {}, fieldErrors: [{field: 'competition.timeZone', code: 'validation.invalid', parameters: {}}]}))
  render(<CompetitionWizard onCancel={vi.fn()} onSaved={vi.fn()}/> )
  fireEvent.change(screen.getByLabelText('Title'), {target: {value: 'River'}})
  fireEvent.change(screen.getByLabelText('Start date'), {target: {value: '2026-04-01'}})
  fireEvent.change(screen.getByLabelText('Location'), {target: {value: 'Bank'}})
  fireEvent.change(screen.getByLabelText('Time zone (IANA)'), {target: {value: 'UTC'}})
  for (let i = 0; i < 4; i++) fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Save'}))
  fireEvent.click(await screen.findByRole('button', {name: 'Time zone (IANA)'}))
  expect(screen.getByLabelText('Time zone (IANA)')).toHaveFocus()
})
test('rejects invalid time zones and nonpositive or fractional slalom counts', () => {
  vi.mocked(apiRequest).mockResolvedValue({content: [], totalPages: 0})
  render(<CompetitionWizard onCancel={vi.fn()} onSaved={vi.fn()}/> )
  fireEvent.change(screen.getByLabelText('Title'), {target: {value: 'River'}})
  fireEvent.change(screen.getByLabelText('Start date'), {target: {value: '2026-04-01'}})
  fireEvent.change(screen.getByLabelText('Location'), {target: {value: 'Bank'}})
  fireEvent.change(screen.getByLabelText('Time zone (IANA)'), {target: {value: 'invalid/zone'}})
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Time zone (IANA)'}))
  expect(screen.getByLabelText('Time zone (IANA)')).toHaveFocus()
  fireEvent.change(screen.getByLabelText('Time zone (IANA)'), {target: {value: 'UTC'}})
  for (let i = 0; i < 3; i++) fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  fireEvent.click(screen.getByRole('button', {name: 'Add discipline'}))
  fireEvent.change(screen.getByLabelText('Name'), {target: {value: 'Slalom'}})
  fireEvent.mouseDown(screen.getByRole('combobox', {name: 'Type'}))
  fireEvent.click(within(screen.getByRole('listbox')).getByRole('option', {name: 'Slalom'}))
  fireEvent.change(screen.getByLabelText('Gates'), {target: {value: '-1'}})
  fireEvent.change(screen.getByLabelText('Attempts'), {target: {value: '1.5'}})
  fireEvent.click(screen.getByRole('button', {name: 'Next'}))
  expect(screen.getByRole('button', {name: 'Gates'})).toBeInTheDocument()
  expect(screen.getByRole('button', {name: 'Attempts'})).toBeInTheDocument()
})
