import {useEffect, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {parseTime} from './result-time'

type Competition = {id: number, title: string}
type Discipline = {id: number, name: string, type: string, categoryIds: number[], gateCount: number | null, attemptCount: number | null}
type Category = {id: number, displayName: string}
type Slot = {crewId: number, crewName: string, startNumber: number, position: number}
type Result = {crewId: number, attemptNumber: number, version: number, durationCentiseconds: number | null, startInstant: string | null, finishInstant: string | null, penaltyCentiseconds: number, status: string, gates: {number: number, penaltySeconds: number}[]}
type Standing = {crewId: number, place: number | null, totalCentiseconds: number | null, status: string}
type Grid = {attempts: Result[], standings: Standing[]}
type Row = {duration: string, penalty: string, status: string, gates: Record<number, number>, saved?: Result, dirty: boolean}

const formatTime = (value: number | null) => value === null ? '' : `${String(Math.floor(value / 6000)).padStart(2, '0')}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}.${String(value % 100).padStart(2, '0')}`
const empty = (): Row => ({duration: '', penalty: '0', status: 'FINISHED', gates: {}, dirty: false})
const fromResult = (r: Result): Row => ({duration: formatTime(r.durationCentiseconds), penalty: formatTime(r.penaltyCentiseconds), status: r.status, gates: Object.fromEntries(r.gates.map(g => [g.number, g.penaltySeconds])), saved: r, dirty: false})

export default function ResultGrid() {
  const {t} = useTranslation()
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(params.get('competition') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
  const [category, setCategory] = useState(params.get('category') ?? '')
  const [attempt, setAttempt] = useState(1)
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [slots, setSlots] = useState<Slot[]>([])
  const [results, setResults] = useState<Result[]>([])
  const [standings, setStandings] = useState<Standing[]>([])
  const [rows, setRows] = useState<Record<number, Row>>({})
  const [gate, setGate] = useState(1)
  const [error, setError] = useState<'' | 'error' | 'invalid'>('')
  const [busy, setBusy] = useState(false)
  const selected = disciplines.find(d => String(d.id) === discipline)
  const base = `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}`
  useEffect(() => {let active = true; const load = async () => {const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100'); const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`))); if (active) setCompetitions([...first.content, ...rest.flatMap(p => p.content)])}; void load().catch(() => active && setError('error')); return () => {active = false}}, [])
  useEffect(() => {if (!competition) return; let active = true; Promise.all([apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`), apiRequest<Category[]>(`/api/competitions/${competition}/categories`)]).then(([d, c]) => {if (active) {setDisciplines(d); setCategories(c)}}).catch(() => active && setError('error')); return () => {active = false}}, [competition])
  useEffect(() => {if (!competition || !discipline || !category) return; let active = true; Promise.all([apiRequest<{slots: Slot[]}>(`${base}/start-list`), apiRequest<Grid>(`${base}/attempt-grid`)]).then(([list, data]) => {if (active) {setSlots(list.slots); setResults(data.attempts); setStandings(data.standings); setRows(Object.fromEntries(list.slots.map(s => {const r = data.attempts.find(x => x.crewId === s.crewId && x.attemptNumber === 1); return [s.crewId, r ? fromResult(r) : empty()]}))); setAttempt(1)}}).catch(() => active && setError('error')); return () => {active = false}}, [base, competition, discipline, category])
  const dirty = Object.values(rows).some(r => r.dirty)
  const canLeave = () => !dirty || window.confirm(t('resultGrid.discard'))
  useEffect(() => {const listener = (event: Event) => {if (dirty && !window.confirm(t('resultGrid.discard'))) event.preventDefault()}; window.addEventListener('category-navigation', listener); return () => window.removeEventListener('category-navigation', listener)}, [dirty, t])
  useEffect(() => {const listener = (event: BeforeUnloadEvent) => {if (dirty) {event.preventDefault(); event.returnValue = ''}}; window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener)}, [dirty])
  const change = (id: number, patch: Partial<Row>) => setRows(old => ({...old, [id]: {...old[id], ...patch, dirty: true}}))
  const save = async (id: number, index: number) => {
    const row = rows[id]; if (!row || !row.dirty || busy) return
    const duration = row.status === 'DNS' || row.status === 'DNF' ? null : row.duration.trim() ? parseTime(row.duration) : null
    const penalty = row.status === 'DNS' || selected?.type === 'SLALOM' ? 0 : parseTime(row.penalty)
    if ((selected?.type === 'SLALOM' && row.status === 'FINISHED' && Object.keys(row.gates).length !== selected.gateCount) || (row.status !== 'DNS' && row.status !== 'DNF' && row.duration.trim() && duration === null) || penalty === null || (row.status === 'FINISHED' && duration === null)) {setError('invalid'); return}
    if (row.status.startsWith('DSQ') && !window.confirm(t('resultGrid.confirmDsq'))) return
    setBusy(true); setError('')
    try {
      const fields = {
        durationCentiseconds: duration,
        startInstant: row.status !== 'DNS' && duration === row.saved?.durationCentiseconds ? row.saved?.startInstant ?? null : null,
        finishInstant: row.status !== 'DNS' && duration === row.saved?.durationCentiseconds ? row.saved?.finishInstant ?? null : null,
        penaltyCentiseconds: penalty,
        status: row.status,
        gates: selected?.type === 'SLALOM' && row.status !== 'DNS' ? Object.entries(row.gates).map(([number, penaltySeconds]) => ({number: Number(number), penaltySeconds})) : [],
      }
      const response = await csrfRequest<{version: number}>(`${base}/crews/${id}/attempts/${attempt}`, 'PUT', {version: row.saved?.version ?? null, ...fields})
      const saved: Result = {crewId: id, attemptNumber: attempt, version: response.version, ...fields}
      setRows(old => ({...old, [id]: fromResult(saved)}))
      setResults(old => [...old.filter(r => r.crewId !== id || r.attemptNumber !== attempt), saved])
      try {const data = await apiRequest<Grid>(`${base}/attempt-grid`); setResults(data.attempts); setStandings(data.standings)} catch {setError('error')}
      setTimeout(() => document.querySelector<HTMLElement>(`[data-row="${index + 1}"]:not(:disabled)`)?.focus(), 0)
    } catch {setError('error')} finally {setBusy(false)}
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    <TextField select label={t('startList.competition')} value={competition} disabled={busy} onChange={e => {if (canLeave()) {setCompetition(e.target.value); setGate(1); setDiscipline(''); setCategory(''); setSlots([]); setResults([])}}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>
    {competition && <TextField select label={t('startList.discipline')} value={discipline} disabled={busy} onChange={e => {if (canLeave()) {setDiscipline(e.target.value); setGate(1); setCategory(''); setSlots([]); setResults([])}}}><MenuItem value="">—</MenuItem>{disciplines.filter(d => d.type !== 'PARALLEL_SPRINT').map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}</TextField>}
    {discipline && <TextField select label={t('startList.category')} value={category} disabled={busy} onChange={e => {if (canLeave()) {setCategory(e.target.value); setGate(1); setSlots([]); setResults([])}}}><MenuItem value="">—</MenuItem>{categories.filter(c => selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {category && selected?.type === 'SLALOM' && <><TextField type="number" label={t('resultGrid.attempt')} disabled={busy} value={attempt} slotProps={{htmlInput: {min: 1, max: selected.attemptCount ?? 1}}} onChange={e => {const n = Number(e.target.value); if (Number.isInteger(n) && n >= 1 && n <= (selected.attemptCount ?? 1) && canLeave()) {setAttempt(n); setRows(Object.fromEntries(slots.map(s => {const r = results.find(x => x.crewId === s.crewId && x.attemptNumber === n); return [s.crewId, r ? fromResult(r) : empty()]})))}}}/><TextField type="number" label={t('resultGrid.gatePage')} value={gate} slotProps={{htmlInput: {min: 1, max: Math.max(1, Math.ceil((selected.gateCount ?? 1) / 20))}}} onChange={e => {const n = Number(e.target.value); if (Number.isInteger(n) && n >= 1 && n <= Math.ceil((selected.gateCount ?? 1) / 20)) setGate(n)}}/></>}
    {category && <Typography>{t('resultGrid.statusHelp')}</Typography>}
    {error && <Typography role="alert">{t(`resultGrid.${error}`)}</Typography>}
    {category && <Box sx={{overflow: 'auto', maxHeight: '65vh'}}><Box component="table" sx={{borderCollapse: 'collapse', '& th': {position: 'sticky', top: 0, backgroundColor: 'background.paper', zIndex: 2}, '& th:nth-of-type(1), & td:nth-of-type(1)': {position: 'sticky', left: 0, minWidth: 180, backgroundColor: 'background.paper', zIndex: 3}, '& th:nth-of-type(2), & td:nth-of-type(2)': {position: 'sticky', left: 180, minWidth: 70, backgroundColor: 'background.paper', zIndex: 3}, '& th:nth-of-type(3), & td:nth-of-type(3)': {position: 'sticky', left: 250, backgroundColor: 'background.paper', zIndex: 3}}} onKeyDown={e => {const target = e.target as HTMLElement; const row = Number(target.dataset.row); const col = Number(target.dataset.col); if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {e.preventDefault(); const slot = slots[row]; if (slot) void save(slot.crewId, row)} else if (!(target instanceof HTMLSelectElement) && ['Enter', 'ArrowDown', 'ArrowUp'].includes(e.key) && Number.isInteger(row) && Number.isInteger(col)) {e.preventDefault(); const next = e.key === 'ArrowUp' ? row - 1 : row + 1; document.querySelector<HTMLInputElement>(`[data-row="${next}"][data-col="${col}"]`)?.focus()}}}>
      <thead><tr><th>{t('startList.crew')}</th><th>{t('resultGrid.attempt')}</th><th>{t('resultGrid.time')}</th><th>{t('resultGrid.penalty')}</th><th>{t('resultGrid.status')}</th>{selected?.type === 'SLALOM' && Array.from({length: Math.min(20, Math.max(0, (selected.gateCount ?? 0) - (gate - 1) * 20))}, (_, i) => <th key={i}>{t('resultGrid.gate')} {(gate - 1) * 20 + i + 1}</th>)}<th>{t('resultGrid.total')}</th><th>{t('resultGrid.best')}</th><th>{t('resultGrid.place')}</th><th>{t('resultGrid.save')}</th></tr></thead>
      <tbody>{slots.map((slot, index) => {
        const row = rows[slot.crewId]
        if (!row) return null
        const standing = standings.find(s => s.crewId === slot.crewId)
        const gateNumbers = Array.from(
          {length: Math.min(20, Math.max(0, (selected?.gateCount ?? 0) - (gate - 1) * 20))},
          (_, i) => (gate - 1) * 20 + i + 1,
        )
        const saved = row.saved
        const total = saved?.status === 'FINISHED' && saved.durationCentiseconds !== null
          ? formatTime(saved.durationCentiseconds + (selected?.type === 'SLALOM'
            ? saved.gates.reduce((sum, g) => sum + g.penaltySeconds * 100, 0)
            : saved.penaltyCentiseconds)) : '—'
        return <tr key={slot.crewId}>
          <td>{slot.position}. {slot.startNumber} — {slot.crewName}{row.dirty ? ' *' : ''}</td>
          <td>{attempt}</td>
          <td><input aria-label={`${t('resultGrid.time')} ${slot.crewName}`} data-row={index} data-col="0"
            value={row.duration} disabled={busy || row.status === 'DNS' || row.status === 'DNF'}
            onChange={e => change(slot.crewId, {duration: e.target.value})}/></td>
          <td>{selected?.type === 'SLALOM'
            ? <span aria-label={`${t('resultGrid.penalty')} ${slot.crewName}`}>{Object.values(row.gates).reduce((sum, value) => sum + value, 0)} s</span>
            : <input aria-label={`${t('resultGrid.penalty')} ${slot.crewName}`} data-row={index} data-col="1"
              value={row.penalty} disabled={busy || row.status === 'DNS'}
              onChange={e => change(slot.crewId, {penalty: e.target.value})}/>}</td>
          <td><select aria-label={`${t('resultGrid.status')} ${slot.crewName}`} data-row={index} data-col="2"
            value={row.status} disabled={busy} onChange={e => change(slot.crewId, {status: e.target.value})}>
            {(['FINISHED','DNS','DNF','DSQ_ATTEMPT','DSQ_DISCIPLINE','DSQ_COMPETITION'] as const).map(status =>
              <option key={status} value={status}>{t(`resultGrid.statuses.${status}`)}</option>)}
          </select></td>
          {selected?.type === 'SLALOM' && gateNumbers.map((number, i) => <td key={number}>
            <select aria-label={`${t('resultGrid.gate')} ${number} ${slot.crewName}`} data-row={index} data-col={3 + i}
              value={row.gates[number] ?? ''} disabled={busy || row.status === 'DNS'} onChange={e => {
                const gates = {...row.gates}
                if (e.target.value === '') delete gates[number]
                else gates[number] = Number(e.target.value)
                change(slot.crewId, {gates})
              }}>
              <option value="">—</option>{[0,5,50].map(n => <option key={n}>{n}</option>)}
            </select>
          </td>)}
          <td>{total}</td>
          <td>{formatTime(standing?.totalCentiseconds ?? null) || '—'}</td>
          <td>{standing?.place ?? '—'}</td>
          <td><Button disabled={!row.dirty || busy} onClick={() => void save(slot.crewId, index)}>{t('resultGrid.save')}</Button></td>
        </tr>
      })}</tbody>
    </Box></Box>}
  </Box>
}
