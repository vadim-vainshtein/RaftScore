import {useEffect, useRef, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey, type ApiErrorKey} from './api/errors'
import {parseTime} from './result-time'


type Competition = {id: number, title: string}
type Category = {id: number, displayName: string}
type Discipline = {id: number, name: string, type: string, status: string, categoryIds: number[]}
type Status = 'FINISHED' | 'DNS' | 'DNF' | 'DSQ_ATTEMPT' | 'DSQ_DISCIPLINE' | 'DSQ_COMPETITION'
type Crew = {id: number, name: string, startNumber: number}
type Heat = {id: number, version: number, round: number, number: number, first: Crew | null, second: Crew | null, firstStatus: Status | null, secondStatus: Status | null, firstTimeCentiseconds: number | null, secondTimeCentiseconds: number | null, winnerSlot: number | null, winnerOverridden: boolean, firstResolved?: boolean, secondResolved?: boolean}
type Bracket = {id: number | null, heats: Heat[]}
type Draft = {version: number, firstStatus: Status, secondStatus: Status, firstTime: string, secondTime: string, winner: string}
const statuses: Status[] = ['FINISHED', 'DNS', 'DNF', 'DSQ_ATTEMPT', 'DSQ_DISCIPLINE', 'DSQ_COMPETITION']
const time = (value: number | null) => value === null ? '' : `${Math.floor(value / 6000)}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}.${String(value % 100).padStart(2, '0')}`
const draftFor = (h: Heat): Draft => ({version: h.version, firstStatus: h.firstStatus ?? 'FINISHED', secondStatus: h.secondStatus ?? 'FINISHED', firstTime: time(h.firstTimeCentiseconds), secondTime: time(h.secondTimeCentiseconds), winner: h.winnerOverridden ? String(h.winnerSlot === 1 ? h.first?.id : h.second?.id) : ''})

export default function ParallelSprint({readOnly, fixedCompetition}: {readOnly: boolean, fixedCompetition?: string}) {
  const {t} = useTranslation()

  const displayTime = (value: number | null) => value === null ? '—' : time(value).padStart(8, '0')
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(fixedCompetition ?? params.get('competition') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
  const [category, setCategory] = useState(params.get('category') ?? '')
  useEffect(() => {
    if (!fixedCompetition) return
    const [route, query = ''] = window.location.hash.slice(1).split('?')
    const next = new URLSearchParams(query)
    if (discipline) next.set('discipline', discipline); else next.delete('discipline')
    if (category) next.set('category', category); else next.delete('category')
    window.history.replaceState(window.history.state, '', `#${route}${next.size ? '?' + next : ''}`)
    window.dispatchEvent(new Event('workspace-filter-change'))
  }, [fixedCompetition, discipline, category])
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [, setSelectedRound] = useState(1)
  const [selectedHeat, setSelectedHeat] = useState<number | null>(null)
  const [followCrew, setFollowCrew] = useState('')
  const [bracket, setBracket] = useState<Bracket | null>(null)
  const [drafts, setDrafts] = useState<Record<number, Draft>>({})
  const [conflict, setConflict] = useState<number | null>(null)
  const [dependentHeats, setDependentHeats] = useState<number[]>([])
  const [error, setError] = useState<ApiErrorKey | 'parallel.invalid' | 'parallel.qualificationIncomplete' | 'parallel.recordedHeats' | ''>('')
  const [busy, setBusy] = useState(false)
  const selected = disciplines.find(d => String(d.id) === discipline)
  const finalRound = Math.max(0, ...(bracket?.heats.map(h => h.round) ?? []))
  const hasBronzeFinal = bracket?.heats.some(h => h.round === finalRound && h.number === 2) ?? false
  const seededCrews = bracket?.heats.filter(h => h.round === 1).reduce((count, h) => count + Number(!!h.first) + Number(!!h.second), 0) ?? 0
  const base = `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}`
  useEffect(() => {let active = true; const load = async () => {
      if (fixedCompetition) {const item = await apiRequest<Competition>(`/api/competitions/${fixedCompetition}`); if (active) setCompetitions([item]); return}const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100'); const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`))); if (active) setCompetitions([...first.content, ...rest.flatMap(p => p.content)])}; void load().catch(e => active && setError(apiErrorKey(e))); return () => {active = false}}, [fixedCompetition])
  useEffect(() => {if (!competition) return; let active = true; Promise.all([apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`), apiRequest<Category[]>(`/api/competitions/${competition}/categories`)]).then(([ds, cs]) => {if (active) {const compatible = ds.filter(d => d.type === 'PARALLEL_SPRINT'); setDisciplines(compatible); setCategories(cs); setDiscipline(current => current && !compatible.some(d => String(d.id) === current) ? '' : current)}}).catch(e => active && setError(apiErrorKey(e))); return () => {active = false}}, [competition])
  useEffect(() => {if (!competition || !selected || !selected.categoryIds.includes(Number(category))) return; let active = true; apiRequest<Bracket>(`${base}/bracket`).then(b => {if (active) {setBracket(b); setSelectedRound(Number(params.get('round')) || b.heats[0]?.round || 1); setSelectedHeat(b.heats.find(h => h.round === Number(params.get('round')) && h.number === Number(params.get('heat')))?.id ?? null); setDrafts({}); setConflict(null)}}).catch(e => active && setError(apiErrorKey(e))); return () => {active = false}}, [base, competition, selected, category])
  const completionNavigation = useRef(false)
  useEffect(() => {const listener = () => {completionNavigation.current = true}; window.addEventListener('result-completed', listener); return () => window.removeEventListener('result-completed', listener)}, [])
  const dirty = bracket?.heats.some(heat => {
    const draft = drafts[heat.id]
    if (!draft) return false
    const saved = draftFor(heat)
    return draft.firstStatus !== saved.firstStatus || draft.secondStatus !== saved.secondStatus
      || draft.firstTime !== saved.firstTime || draft.secondTime !== saved.secondTime || draft.winner !== saved.winner
  }) ?? false
  useEffect(() => {
    const listener = (event: Event) => {
      if (!completionNavigation.current && (busy || (dirty && !window.confirm(t('resultGrid.discard'))))) event.preventDefault()
    }
    window.addEventListener('category-navigation', listener)
    return () => window.removeEventListener('category-navigation', listener)
  }, [busy, dirty, t])
  useEffect(() => {const listener = (event: BeforeUnloadEvent) => {if (dirty && !completionNavigation.current) {event.preventDefault(); event.returnValue = ''}}; window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener)}, [dirty])
  const canLeave = () => !dirty || window.confirm(t('resultGrid.discard'))
  useEffect(() => {const listener = (event: Event) => {const detail = (event as CustomEvent<{count: number, busy: boolean}>).detail; detail.count += bracket?.heats.filter(heat => {const draft = drafts[heat.id]; return draft && JSON.stringify(draft) !== JSON.stringify(draftFor(heat))}).length ?? 0; detail.busy ||= busy}; window.addEventListener('result-dirty-count', listener); return () => window.removeEventListener('result-dirty-count', listener)}, [bracket, drafts, busy])
  const clearSelection = () => {setSelectedHeat(null); setFollowCrew(''); setBracket(null); setDrafts({}); setConflict(null); setError('')}
  const refresh = async () => {const updated = await apiRequest<Bracket>(`${base}/bracket`); setBracket(updated); setSelectedRound(current => updated.heats.some(heat => heat.round === current) ? current : updated.heats[0]?.round ?? 1)}
  const seed = async () => {if (readOnly || selected?.status !== 'STARTED') return; setBusy(true); setError(''); try {const seeded = await csrfRequest<Bracket>(`${base}/bracket`, 'POST'); setBracket(seeded); setSelectedRound(seeded.heats[0]?.round ?? 1)} catch (e) {setError(e instanceof ApiError && e.code === 'parallel.qualification_incomplete' ? 'parallel.qualificationIncomplete' : apiErrorKey(e))} finally {setBusy(false)}}
  const rebuild = async () => {
    if (!bracket?.id || !canLeave() || !window.confirm(t('parallel.rebuildConfirm'))) return
    setBusy(true); setError(''); setDependentHeats([])
    try {
      const updated = await csrfRequest<Bracket>(`${base}/bracket/rebuild`, 'POST', {bracketId: bracket.id})
      setBracket(updated); setDrafts({}); setConflict(null); setSelectedRound(updated.heats[0]?.round ?? 1)
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'parallel.recorded_heats' ? 'parallel.recordedHeats'
        : e instanceof ApiError && e.code === 'parallel.qualification_incomplete' ? 'parallel.qualificationIncomplete' : apiErrorKey(e))
    } finally {setBusy(false)}
  }
  const addBronze = async () => {if (!canLeave() || !window.confirm(t('parallel.upgradeConfirm'))) return; setBusy(true); setError(''); try {const updated = await csrfRequest<Bracket>(`${base}/bracket/bronze-final`, 'POST'); setBracket(updated); setDrafts({}); setConflict(null)} catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}}
  const save = async (h: Heat) => {
    const draft = drafts[h.id] ?? draftFor(h)
    const first = draft.firstStatus === 'FINISHED' ? parseTime(draft.firstTime) : null
    const second = draft.secondStatus === 'FINISHED' ? parseTime(draft.secondTime) : null
    if ((draft.firstStatus === 'FINISHED' && first === null) || (draft.secondStatus === 'FINISHED' && second === null)) {setError('parallel.invalid'); return}
    if (draft.winner && !window.confirm(t('parallel.confirmWinner'))) return
    setBusy(true); setError('')
    try {
      await csrfRequest(`${base}/brackets/${bracket?.id}/heats/${h.id}/result`, 'PUT', {version: draft.version, first: {status: draft.firstStatus, timeCentiseconds: first}, second: {status: draft.secondStatus, timeCentiseconds: second}, winnerCrewId: draft.winner ? Number(draft.winner) : null, confirmed: !!draft.winner})
      await refresh(); setDrafts(old => {const next = {...old}; delete next[h.id]; return next}); setConflict(null)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {try {await refresh(); setConflict(h.id)} catch (refreshError) {setError(apiErrorKey(refreshError))}} else setError(apiErrorKey(e))
    } finally {setBusy(false)}
  }
  const annul = async (h: Heat) => {
    if (!window.confirm(t('parallel.confirmAnnul'))) return
    const reason = window.prompt(t('startList.annulReason'))?.trim()
    if (!reason) return
    setBusy(true); setError(''); setDependentHeats([])
    try {
      await csrfRequest(`${base}/brackets/${bracket?.id}/heats/${h.id}/annulment`, 'POST', {version: h.version, reason})
      await refresh(); setDrafts(old => {const next = {...old}; delete next[h.id]; return next})
    } catch (e) {
      if (e instanceof ApiError && e.code === 'parallel.dependent_heats' && Array.isArray(e.parameters?.heatIds)) {
        setDependentHeats(e.parameters.heatIds.filter((id): id is number => typeof id === 'number'))
      } else setError(apiErrorKey(e))
    } finally {setBusy(false)}
  }
  const update = (h: Heat, patch: Partial<Draft>) => setDrafts(old => ({...old, [h.id]: {...(old[h.id] ?? draftFor(h)), ...patch}}))
  return <Box sx={{display: 'grid', gap: 2, mt: 2, minWidth: 0}}>
    {!fixedCompetition && <TextField select disabled={busy || !!fixedCompetition} label={t('results.competition')} value={competition} onChange={e => {if (canLeave()) {setCompetition(e.target.value); setDiscipline(''); setCategory(''); setDisciplines([]); setCategories([]); clearSelection()}}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>}
    {!fixedCompetition && competition && <Button component="a" href={`#/workspace/${competition}`}>{t('competition.workspace')}</Button>}
    {competition && <TextField select disabled={busy} label={t('results.discipline')} value={discipline} onChange={e => {if (canLeave()) {setDiscipline(e.target.value); if (!disciplines.find(d => String(d.id) === e.target.value)?.categoryIds.includes(Number(category))) setCategory(''); clearSelection()}}}><MenuItem value="">—</MenuItem>{disciplines.map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name || t(`discipline.types.${d.type}` as 'discipline.types.SPRINT')}</MenuItem>)}</TextField>}
    {discipline && <TextField select disabled={busy} label={t('results.category')} value={category} onChange={e => {if (canLeave()) {setCategory(e.target.value); clearSelection()}}}><MenuItem value="">—</MenuItem>{categories.filter(c => selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {error && <Typography role="alert">{t(error)}</Typography>}
    {dependentHeats.length > 0 && <Typography role="alert">{t('parallel.dependentHeats')}: {dependentHeats.map(id => {
      const heat = bracket?.heats.find(item => item.id === id)
      return heat ? `${t('parallel.round', {round: heat.round})}, ${t('parallel.heat', {heat: heat.number})}` : String(id)
    }).join('; ')}</Typography>}
    {selected && selected.status !== 'STARTED' && <Typography role="status">{t('discipline.entryClosed')}</Typography>}
    {bracket && !bracket.id && <><Typography>{t('parallel.empty')}</Typography>{!readOnly && selected?.status === 'STARTED' && <Button disabled={busy} onClick={() => void seed()}>{t('parallel.seed')}</Button>}</>}
    {!!bracket?.id && seededCrews >= 4 && !hasBronzeFinal && <><Typography role="status">{t(selected?.status === 'COMPLETED' ? 'parallel.legacyCompleted' : 'parallel.legacy')}</Typography>{!readOnly && selected?.status === 'STARTED' && <Button disabled={busy} onClick={() => void addBronze()}>{t('parallel.upgrade')}</Button>}</>}
    {!!bracket?.id && !readOnly && selected?.status === 'STARTED' && <Button disabled={busy} onClick={() => void rebuild()}>{t('parallel.rebuild')}</Button>}
    {!!bracket?.heats.length && <TextField select label={t('parallel.followCrew')} value={followCrew} onChange={e => setFollowCrew(e.target.value)}><MenuItem value="">{t('parallel.allCrews')}</MenuItem>{Array.from(new Map(bracket.heats.flatMap(h => [h.first, h.second]).filter((c): c is Crew => c !== null).map(c => [c.id, c])).values()).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.startNumber} — {c.name}</MenuItem>)}</TextField>}
    {followCrew && <Box component="section" aria-label={t('parallel.followCrew')} sx={{p: 1, border: 1, borderColor: 'divider'}}>{bracket?.heats.filter(h => [h.first?.id, h.second?.id].includes(Number(followCrew))).map(h => {
      const own = h.first?.id === Number(followCrew) ? h.first : h.second
      const opponent = h.first?.id === Number(followCrew) ? h.second : h.first
      const winner = h.winnerSlot === 1 ? h.first : h.winnerSlot === 2 ? h.second : null
      return <Typography key={h.id}>{h.round === finalRound && hasBronzeFinal ? t(h.number === 2 ? 'parallel.finalB' : 'parallel.finalA') : h.round === finalRound - 1 ? t('parallel.semifinal') : t('parallel.round', {round: h.round})} · {own?.name} — {opponent?.name ?? '—'} · {t('parallel.winner')}: {winner?.name ?? '—'} · {displayTime(h.first?.id === Number(followCrew) ? h.firstTimeCentiseconds : h.secondTimeCentiseconds) || '—'} / {displayTime(h.first?.id === Number(followCrew) ? h.secondTimeCentiseconds : h.firstTimeCentiseconds) || '—'}</Typography>
    })}</Box>}
    <Box sx={{display: {md: 'flex'}, gap: 2, alignItems: 'start', overflowX: 'auto'}}>
    {[...new Set(bracket?.heats.map(h => h.round) ?? [])].map(round => <Box component="section" key={round} sx={{display: 'grid', gap: 2, minWidth: {md: 320}, flex: 1}}><Typography component="h2" variant="h5">{t('parallel.round', {round})}</Typography>{bracket?.heats.filter(h => h.round === round).map(h => {
      const draft = drafts[h.id] ?? draftFor(h)
      return <Box component="section" key={h.id} sx={{border: 1, borderColor: 'divider', borderRadius: 1, p: 2, overflowWrap: 'anywhere', borderLeftWidth: followCrew && [h.first?.id, h.second?.id].includes(Number(followCrew)) ? 5 : 1}}>
        <Button fullWidth aria-pressed={selectedHeat === h.id} aria-label={t(readOnly ? 'parallel.heatDetails' : 'parallel.editHeat', {heat: h.number})} onClick={() => {if (canLeave()) setSelectedHeat(selectedHeat === h.id ? null : h.id)}} sx={{display: 'block', textAlign: 'left', color: 'text.primary', textTransform: 'none', '&[aria-pressed="true"]': {backgroundColor: 'action.selected'}}}>
          <Typography component="span" sx={{display: 'block', fontWeight: 'bold'}}>{round === finalRound && hasBronzeFinal ? t(h.number === 2 ? 'parallel.finalB' : 'parallel.finalA') : t('parallel.heat', {heat: h.number})}</Typography>
          <HeatLanes heat={h}/>
        </Button>
        {selectedHeat === h.id && <HeatDetail heat={h} finalRound={finalRound} hasBronzeFinal={hasBronzeFinal}/>}
        {selectedHeat === h.id && !readOnly && selected?.status === 'STARTED' && h.first && h.second && <><Box sx={{display: 'grid', gap: 1, mt: 1}}>
          {([1, 2] as const).map(i => <Box key={i} sx={{display: 'grid', gap: 1}}><TextField select disabled={busy} label={`${t('results.status')} ${i}`} value={i === 1 ? draft.firstStatus : draft.secondStatus} onChange={e => update(h, i === 1 ? {firstStatus: e.target.value as Status} : {secondStatus: e.target.value as Status})}>{statuses.map(status => <MenuItem key={status} value={status}>{t(`results.${status}`)}</MenuItem>)}</TextField><TextField label={`${t('results.time')} ${t('parallel.slot', {number: i})} — ${i === 1 ? h.first?.name : h.second?.name}`} value={i === 1 ? draft.firstTime : draft.secondTime} disabled={busy || (i === 1 ? draft.firstStatus : draft.secondStatus) !== 'FINISHED'} onChange={e => update(h, i === 1 ? {firstTime: e.target.value} : {secondTime: e.target.value})}/></Box>)}
          <TextField select disabled={busy} label={t('parallel.override')} value={draft.winner} onChange={e => update(h, {winner: e.target.value})}><MenuItem value="">{t('parallel.automatic')}</MenuItem><MenuItem value={String(h.first.id)}>{h.first.name}</MenuItem><MenuItem value={String(h.second.id)}>{h.second.name}</MenuItem></TextField>
          {conflict === h.id && <><Typography role="alert">{t('parallel.conflict')}</Typography><Button disabled={busy} onClick={() => {setDrafts(old => {const next = {...old}; delete next[h.id]; return next}); setConflict(null)}}>{t('parallel.useLatest')}</Button></>}
          <Button disabled={busy || conflict === h.id} onClick={() => void save(h)}>{t('parallel.save')}</Button>
          {(h.firstStatus || h.secondStatus) && <Button disabled={busy} onClick={() => void annul(h)}>{t('parallel.annul')}</Button>}
        </Box></>}
      </Box>
    })}</Box>)}
    </Box>
  </Box>
}

function HeatLanes({heat}: {heat: Heat}) {
  const {t} = useTranslation()
  const lanes = [{crew: heat.first, status: heat.firstStatus, value: heat.firstTimeCentiseconds, resolved: heat.firstResolved}, {crew: heat.second, status: heat.secondStatus, value: heat.secondTimeCentiseconds, resolved: heat.secondResolved}]
  return <Box component="span" sx={{display: 'grid', gap: 0.5}}>{lanes.map((lane, index) => <Box component="span" key={index} sx={{display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 1, fontVariantNumeric: 'tabular-nums'}}>
    <span>{t('parallel.slot', {number: index + 1})}: {lane.crew ? `${lane.crew.startNumber} — ${lane.crew.name}` : t((lane.resolved ?? heat.round === 1) ? 'parallel.bye' : 'parallel.pending')}{heat.winnerSlot === index + 1 && <strong> · {t('parallel.winner')}</strong>}</span>
    <span style={{textAlign: 'right'}}>{lane.value == null ? (lane.status ? t(`results.${lane.status}`) : '—') : time(lane.value).padStart(8, '0')}</span>
  </Box>)}</Box>
}

function HeatDetail({heat, finalRound, hasBronzeFinal}: {heat: Heat, finalRound: number, hasBronzeFinal: boolean}) {
  const {t} = useTranslation()
  const winner = heat.winnerSlot === 1 ? heat.first : heat.winnerSlot === 2 ? heat.second : null
  const stage = heat.round === finalRound && hasBronzeFinal ? t(heat.number === 2 ? 'parallel.finalB' : 'parallel.finalA') : heat.round === finalRound - 1 ? t('parallel.semifinal') : t('parallel.round', {round: heat.round})
  return <Box sx={{p: 1, backgroundColor: 'action.hover'}}><Typography>{stage} · {t('parallel.heat', {heat: heat.number})}</Typography><Typography>{t('parallel.winner')}: {winner?.name ?? '—'}{heat.winnerOverridden ? ` (${t('parallel.manual')})` : ''}</Typography>{[heat.firstStatus, heat.secondStatus].map((status, i) => <Typography key={i}>{t('parallel.slot', {number: i + 1})}: {status ? t(`results.${status}`) : '—'}</Typography>)}</Box>
}
