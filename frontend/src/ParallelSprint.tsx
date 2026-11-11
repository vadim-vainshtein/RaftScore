import {useEffect, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {parseTime} from './result-time'
import {supportedLocale} from './i18n/supported-locales'

type Competition = {id: number, title: string}
type Category = {id: number, displayName: string}
type Discipline = {id: number, name: string, type: string, categoryIds: number[]}
type Status = 'FINISHED' | 'DNS' | 'DNF' | 'DSQ_ATTEMPT' | 'DSQ_DISCIPLINE' | 'DSQ_COMPETITION'
type Crew = {id: number, name: string, startNumber: number}
type Heat = {id: number, version: number, round: number, number: number, first: Crew | null, second: Crew | null, firstStatus: Status | null, secondStatus: Status | null, firstTimeCentiseconds: number | null, secondTimeCentiseconds: number | null, winnerSlot: number | null, winnerOverridden: boolean}
type Bracket = {id: number | null, heats: Heat[]}
type Draft = {version: number, firstStatus: Status, secondStatus: Status, firstTime: string, secondTime: string, winner: string}
const statuses: Status[] = ['FINISHED', 'DNS', 'DNF', 'DSQ_ATTEMPT', 'DSQ_DISCIPLINE', 'DSQ_COMPETITION']
const time = (value: number | null) => value === null ? '' : `${Math.floor(value / 6000)}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}.${String(value % 100).padStart(2, '0')}`
const draftFor = (h: Heat): Draft => ({version: h.version, firstStatus: h.firstStatus ?? 'FINISHED', secondStatus: h.secondStatus ?? 'FINISHED', firstTime: time(h.firstTimeCentiseconds), secondTime: time(h.secondTimeCentiseconds), winner: h.winnerOverridden ? String(h.winnerSlot === 1 ? h.first?.id : h.second?.id) : ''})

export default function ParallelSprint({readOnly}: {readOnly: boolean}) {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const displayTime = (value: number | null) => time(value).replace('.', locale === 'ru' ? ',' : '.')
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(params.get('competition') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
  const [category, setCategory] = useState(params.get('category') ?? '')
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [bracket, setBracket] = useState<Bracket | null>(null)
  const [drafts, setDrafts] = useState<Record<number, Draft>>({})
  const [conflict, setConflict] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const selected = disciplines.find(d => String(d.id) === discipline)
  const base = `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}`
  useEffect(() => {let active = true; const load = async () => {const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100'); const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`))); if (active) setCompetitions([...first.content, ...rest.flatMap(p => p.content)])}; void load().catch(() => active && setError('error')); return () => {active = false}}, [])
  useEffect(() => {if (!competition) return; let active = true; Promise.all([apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`), apiRequest<Category[]>(`/api/competitions/${competition}/categories`)]).then(([ds, cs]) => {if (active) {setDisciplines(ds.filter(d => d.type === 'PARALLEL_SPRINT')); setCategories(cs)}}).catch(() => active && setError('error')); return () => {active = false}}, [competition])
  useEffect(() => {if (!competition || !discipline || !category) return; let active = true; apiRequest<Bracket>(`${base}/bracket`).then(b => {if (active) {setBracket(b); setDrafts({}); setConflict(null)}}).catch(() => active && setError('error')); return () => {active = false}}, [base, competition, discipline, category])
  const clearSelection = () => {setBracket(null); setDrafts({}); setConflict(null); setError('')}
  const refresh = async () => setBracket(await apiRequest<Bracket>(`${base}/bracket`))
  const seed = async () => {setBusy(true); setError(''); try {setBracket(await csrfRequest<Bracket>(`${base}/bracket`, 'POST'))} catch {setError('error')} finally {setBusy(false)}}
  const save = async (h: Heat) => {
    const draft = drafts[h.id] ?? draftFor(h)
    const first = draft.firstStatus === 'FINISHED' ? parseTime(draft.firstTime) : null
    const second = draft.secondStatus === 'FINISHED' ? parseTime(draft.secondTime) : null
    if ((draft.firstStatus === 'FINISHED' && first === null) || (draft.secondStatus === 'FINISHED' && second === null)) {setError('invalid'); return}
    if (draft.winner && !window.confirm(t('parallel.confirmWinner'))) return
    setBusy(true); setError('')
    try {
      await csrfRequest(`${base}/brackets/${bracket?.id}/heats/${h.id}/result`, 'PUT', {version: draft.version, first: {status: draft.firstStatus, timeCentiseconds: first}, second: {status: draft.secondStatus, timeCentiseconds: second}, winnerCrewId: draft.winner ? Number(draft.winner) : null, confirmed: !!draft.winner})
      await refresh(); setDrafts(old => {const next = {...old}; delete next[h.id]; return next}); setConflict(null)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {try {await refresh(); setConflict(h.id)} catch {setError('error')}} else setError('error')
    } finally {setBusy(false)}
  }
  const update = (h: Heat, patch: Partial<Draft>) => setDrafts(old => ({...old, [h.id]: {...(old[h.id] ?? draftFor(h)), ...patch}}))
  return <Box sx={{display: 'grid', gap: 2, mt: 2, minWidth: 0}}>
    <TextField select disabled={busy} label={t('results.competition')} value={competition} onChange={e => {setCompetition(e.target.value); setDiscipline(''); setCategory(''); setDisciplines([]); setCategories([]); clearSelection()}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>
    {competition && <TextField select disabled={busy} label={t('results.discipline')} value={discipline} onChange={e => {setDiscipline(e.target.value); setCategory(''); clearSelection()}}><MenuItem value="">—</MenuItem>{disciplines.map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}</TextField>}
    {discipline && <TextField select disabled={busy} label={t('results.category')} value={category} onChange={e => {setCategory(e.target.value); clearSelection()}}><MenuItem value="">—</MenuItem>{categories.filter(c => selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {error && <Typography role="alert">{t(error === 'invalid' ? 'parallel.invalid' : 'parallel.error')}</Typography>}
    {bracket && !bracket.id && <><Typography>{t('parallel.empty')}</Typography>{!readOnly && <Button disabled={busy} onClick={() => void seed()}>{t('parallel.seed')}</Button>}</>}
    {[...new Set(bracket?.heats.map(h => h.round) ?? [])].map(round => <Box component="section" key={round} sx={{display: 'grid', gap: 2}}><Typography component="h2" variant="h5">{t('parallel.round', {round})}</Typography>{bracket?.heats.filter(h => h.round === round).map(h => {
      const draft = drafts[h.id] ?? draftFor(h)
      const winner = h.winnerSlot === 1 ? h.first : h.winnerSlot === 2 ? h.second : null
      return <Box component="section" key={h.id} sx={{border: 1, borderColor: 'divider', borderRadius: 1, p: 2, overflowWrap: 'anywhere'}}>
        <Typography component="h3" variant="h6">{t('parallel.heat', {heat: h.number})}</Typography>
        {[{crew: h.first, status: h.firstStatus, value: h.firstTimeCentiseconds}, {crew: h.second, status: h.secondStatus, value: h.secondTimeCentiseconds}].map((slot, i) => <Typography key={i}>{t('parallel.slot', {number: i + 1})}: {slot.crew ? `${slot.crew.startNumber} — ${slot.crew.name}` : t(h.round === 1 ? 'parallel.bye' : 'parallel.pending')} · {slot.status ? t(`results.${slot.status}`) : '—'} · {displayTime(slot.value) || '—'}</Typography>)}
        <Typography>{t('parallel.winner')}: {winner?.name ?? '—'}{h.winnerOverridden ? ` (${t('parallel.manual')})` : ''}</Typography>
        {!readOnly && h.first && h.second && <Box sx={{display: 'grid', gap: 1, mt: 1}}>
          {([1, 2] as const).map(i => <Box key={i} sx={{display: 'grid', gap: 1}}><TextField select disabled={busy} label={`${t('results.status')} ${i}`} value={i === 1 ? draft.firstStatus : draft.secondStatus} onChange={e => update(h, i === 1 ? {firstStatus: e.target.value as Status} : {secondStatus: e.target.value as Status})}>{statuses.map(status => <MenuItem key={status} value={status}>{t(`results.${status}`)}</MenuItem>)}</TextField><TextField label={`${t('results.time')} ${i}`} value={i === 1 ? draft.firstTime : draft.secondTime} disabled={busy || (i === 1 ? draft.firstStatus : draft.secondStatus) !== 'FINISHED'} onChange={e => update(h, i === 1 ? {firstTime: e.target.value} : {secondTime: e.target.value})}/></Box>)}
          <TextField select disabled={busy} label={t('parallel.override')} value={draft.winner} onChange={e => update(h, {winner: e.target.value})}><MenuItem value="">{t('parallel.automatic')}</MenuItem><MenuItem value={String(h.first.id)}>{h.first.name}</MenuItem><MenuItem value={String(h.second.id)}>{h.second.name}</MenuItem></TextField>
          {conflict === h.id && <><Typography role="alert">{t('parallel.conflict')}</Typography><Button disabled={busy} onClick={() => {setDrafts(old => {const next = {...old}; delete next[h.id]; return next}); setConflict(null)}}>{t('parallel.useLatest')}</Button></>}
          <Button disabled={busy || conflict === h.id} onClick={() => void save(h)}>{t('parallel.save')}</Button>
        </Box>}
      </Box>
    })}</Box>)}
  </Box>
}
