import {useEffect, useRef, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography, useMediaQuery} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey, type ApiErrorKey} from './api/errors'
import {formatNumber} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Competition = {id: number, title: string}
type Category = {id: number, displayName: string}
type Discipline = {id: number, name: string, type: string, categoryIds: number[]}
type Status = 'FINISHED' | 'DNS' | 'DNF' | 'DSQ_ATTEMPT' | 'DSQ_DISCIPLINE' | 'DSQ_COMPETITION'
type Part = {disciplineId: number, disciplineName: string, place: number | null, status: Status, points: number}
type Attempt = {number: number, durationCentiseconds: number | null, penaltyCentiseconds: number, status: Status, gates: {number: number, penaltySeconds: number}[]}
type Result = {crewId: number, crewName: string, regionOrClub: string, startNumber: number, place: number | null, totalCentiseconds?: number | null, status?: Status, points?: number, totalPoints?: number, disqualified?: boolean, disciplines?: Part[], appliedTiebreakDisciplineId?: number | null, attempts?: Attempt[]}

const formatTime = (value: number | null | undefined) => value == null ? '—' : `${String(Math.floor(value / 6000)).padStart(2, '0')}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}.${String(value % 100).padStart(2, '0')}`

export default function Results({readOnly, fixedCompetition}: {readOnly: boolean, fixedCompetition?: string}) {
  const {t, i18n} = useTranslation()
  const desktop = useMediaQuery('(min-width:900px)')
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(fixedCompetition ?? params.get('competition') ?? '')
  const [category, setCategory] = useState(params.get('category') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
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
  const [categories, setCategories] = useState<Category[]>([])
  useEffect(() => {if (!fixedCompetition) document.title = [t('navigation.results'), competitions.find(c => String(c.id) === competition)?.title, 'RaftScore'].filter(Boolean).join(' — ')}, [fixedCompetition, competition, competitions, t])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [rows, setRows] = useState<Result[] | null>(null)
  const [error, setError] = useState<ApiErrorKey | 'results.error' | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null)
  const [busy, setBusy] = useState(false)
  const [filtersVisible, setFiltersVisible] = useState(true)
  const [fullscreen, setFullscreen] = useState(false)
  useEffect(() => {const update = () => setFullscreen(document.fullscreenElement === display.current); document.addEventListener('fullscreenchange', update); return () => document.removeEventListener('fullscreenchange', update)}, [])
  const display = useRef<HTMLDivElement>(null)
  const [incomplete, setIncomplete] = useState(false)
  const number = (value: number | null | undefined) => value == null ? '—' : formatNumber(value, locale)
  const time = formatTime
  useEffect(() => {
    let active = true
    const load = async () => {
      if (fixedCompetition) {const item = await apiRequest<Competition>(`/api/competitions/${fixedCompetition}`); if (active) setCompetitions([item]); return}
      const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
      const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
      if (active) setCompetitions([...first.content, ...rest.flatMap(page => page.content)])
    }
    void load().catch(e => active && setError(apiErrorKey(e)))
    return () => {active = false}
  }, [fixedCompetition])
  useEffect(() => {
    if (!competition) return
    let active = true
    Promise.all([apiRequest<Category[]>(`/api/competitions/${competition}/categories`), apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`)])
      .then(([cs, ds]) => {if (active) {setCategories(cs); setDisciplines(ds)}}).catch(e => active && setError(apiErrorKey(e)))
    return () => {active = false}
  }, [competition])
  useEffect(() => {
    if (!competition || !category) return
    let active = true
    let pending = false
    const path = discipline ? `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}/results` : `/api/competitions/${competition}/categories/${category}/standings`
    const load = async () => {
      if (pending) return
      pending = true
      try {
        const data = await apiRequest<Result[]>(path)
        if (active) {setRows(data); setUpdatedAt(new Date()); setError(null)}
        const scoped = disciplines.filter(d => d.categoryIds.includes(Number(category)) && (!discipline || String(d.id) === discipline))
        const lists = await Promise.all(scoped.map(async d => {
          const base = `/api/competitions/${competition}/disciplines/${d.id}/categories/${category}`
          if (d.type === 'PARALLEL_SPRINT') {
            const bracket = await apiRequest<{heats: {round: number, first: {id: number} | null, second: {id: number} | null}[]}>(`${base}/bracket`)
            const slots = bracket.heats.filter(h => h.round === 1).flatMap(h => [h.first, h.second]).filter(c => c !== null).map(c => ({crewId: c.id}))
            return {id: d.id, list: {slots}}
          }
          return {id: d.id, list: await apiRequest<{slots: {crewId: number}[]}>(`${base}/start-list`)}
        }))
        const missing = lists.some(({id, list}) => list.slots.some(slot => !data.some(row => row.crewId === slot.crewId && (discipline || row.disciplines?.some(part => part.disciplineId === id)))))
        if (active) setIncomplete(missing)
      }
      catch (e) {if (active) setError(apiErrorKey(e))} finally {pending = false}
    }
    void load()
    const timer = readOnly ? window.setInterval(() => void load(), 10000) : undefined
    return () => {active = false; window.clearInterval(timer)}
  }, [competition, category, discipline, readOnly, refresh, disciplines])
  const selected = disciplines.find(d => String(d.id) === discipline)
  return <Box ref={display} sx={{display: 'grid', alignContent: 'start', gap: 1, mt: 2, overflow: 'auto', '&:fullscreen': {backgroundColor: 'background.paper', p: 2}}}>
    <Box className="section-heading"><Typography component="h2" variant="h6">{t('workspace.protocol')}</Typography></Box>
    <Typography>{[competitions.find(c => String(c.id) === competition)?.title, discipline ? selected && (selected.name || t(`discipline.types.${selected.type}` as 'discipline.types.SPRINT')) : t('results.overall'), categories.find(c => String(c.id) === category)?.displayName].filter(Boolean).join(' · ')}</Typography>
    <Box><Button onClick={() => setFiltersVisible(value => !value)}>{t(filtersVisible ? 'results.hideFilters' : 'results.showFilters')}</Button><Button onClick={() => {if (document.fullscreenElement) void document.exitFullscreen(); else if (display.current?.requestFullscreen) void display.current.requestFullscreen().catch(() => setError('results.error'))}}>{t(fullscreen ? 'results.exitFullscreen' : 'results.fullscreen')}</Button></Box>
    {filtersVisible && <Box className="filter-panel">{!fixedCompetition && <TextField select slotProps={{select: {MenuProps: {container: () => display.current}}}} disabled={!!fixedCompetition} label={t('results.competition')} value={competition} onChange={e => {setCompetition(e.target.value); setCategory(''); setDiscipline(''); setRows(null); setCategories([]); setDisciplines([]); setIncomplete(false); setError(null)}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>}
    {!fixedCompetition && competition && <Button component="a" href={`#/workspace/${competition}`}>{t('competition.workspace')}</Button>}
    {competition && <TextField select slotProps={{select: {MenuProps: {container: () => display.current}}}} label={t('results.category')} value={category} onChange={e => {setCategory(e.target.value); setRows(null); setError(null)}}><MenuItem value="">—</MenuItem>{categories.filter(c => !discipline || selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {competition && <TextField select slotProps={{select: {MenuProps: {container: () => display.current}}}} label={t('results.discipline')} value={discipline} onChange={e => {setDiscipline(e.target.value); if (e.target.value && !disciplines.find(d => String(d.id) === e.target.value)?.categoryIds.includes(Number(category))) setCategory(''); setRows(null); setError(null)}}><MenuItem value="">{t('results.overall')}</MenuItem>{disciplines.map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name || t(`discipline.types.${d.type}` as 'discipline.types.SPRINT')}</MenuItem>)}</TextField>}</Box>}
    {competition && category && <Box><Button onClick={() => setRefresh(value => value + 1)}>{t('results.refresh')}</Button>{updatedAt && <Typography component="span">{t('results.updatedAt', {time: new Intl.DateTimeFormat(locale, {timeStyle: 'medium'}).format(updatedAt)})}</Typography>}{discipline && selected?.type === 'PARALLEL_SPRINT' && <Button component="a" href={`#/workspace/${competition}/parallel-sprint?competition=${competition}&discipline=${discipline}&category=${category}`}>{t('navigation.parallelSprint')}</Button>}{!readOnly && <>{discipline && selected?.type !== 'PARALLEL_SPRINT' && <Button component="a" href={`${fixedCompetition ? `#/workspace/${competition}/result-grid` : '#/result-grid'}?competition=${competition}&discipline=${discipline}&category=${category}`}>{t('results.edit')}</Button>}<Button disabled={busy} onClick={async () => {setBusy(true); try {await csrfRequest(`/api/competitions/${competition}/recalculate`, 'POST'); setRefresh(value => value + 1)} catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}}}>{t('results.recalculate')}</Button></>}</Box>}
    {rows && incomplete && <Typography>{t('results.availableData')}</Typography>}
    {error && <Typography role="alert">{t(error)}</Typography>}
    {category && !rows && !error && <Typography>{t('results.loading')}</Typography>}
    {rows && rows.length === 0 && <Typography>{t('results.empty')}</Typography>}
    {desktop && rows && rows.length > 0 && <Box sx={{overflowX: 'auto'}}><Box component="table" sx={{width: '100%', borderCollapse: 'collapse', fontVariantNumeric: 'tabular-nums', '& th, & td': {textAlign: 'left', borderBottom: 1, borderColor: 'divider', p: 1}}}>
      <thead><tr><th>{t('results.place')}</th><th>{t('results.crew')}</th><th>{t('results.number')}</th><th>{t('results.region')}</th><th>{t('results.status')}</th><th>{t('results.time')}</th><th>{t('results.points')}</th><th>{t('results.attemptDetails')}</th></tr></thead>
      <tbody>{rows.map(row => <tr key={row.crewId}>
        <td>{number(row.place)}</td><td>{row.crewName}</td><td>{number(row.startNumber)}</td><td>{row.regionOrClub || '—'}</td>
        <td>{discipline ? row.status && t(`results.${row.status}`) : t(`results.${overallStatus(row)}`)}</td>
        <td>{discipline && row.status === 'FINISHED' ? time(row.totalCentiseconds) : '—'}</td><td>{number(discipline ? row.points : row.totalPoints)}</td>
        <td><details><summary>{t(discipline ? 'results.attemptDetails' : 'results.breakdown')}</summary>{discipline ? row.attempts && <AttemptDetails attempts={row.attempts} slalom={selected?.type === 'SLALOM'}/> : <OverallBreakdown row={row} disciplines={disciplines.filter(d => d.categoryIds.includes(Number(category)))}/>}</details></td>
      </tr>)}</tbody>
    </Box></Box>}
    {!desktop && rows?.map(row => <Box component="article" key={row.crewId} sx={{border: 1, borderColor: 'divider', borderRadius: 1, p: 2, overflowWrap: 'anywhere'}}>
      <Typography variant="h6">{number(row.place)} — {row.crewName}</Typography>
      <Typography>{t('results.number')}: {number(row.startNumber)} · {t('results.region')}: {row.regionOrClub || '—'}</Typography>
      {discipline ? <><Typography>{t('results.status')}: {row.status && t(`results.${row.status}`)}</Typography><Typography>{t('results.time')}: {row.status === 'FINISHED' ? time(row.totalCentiseconds) : '—'}</Typography><Typography>{t('results.points')}: {number(row.points)}</Typography>{row.attempts && <AttemptDetails attempts={row.attempts} slalom={selected?.type === 'SLALOM'}/>}</> : <><OverallBreakdown row={row} disciplines={disciplines.filter(d => d.categoryIds.includes(Number(category)))}/></>}
    </Box>)}
  </Box>
}


function AttemptDetails({attempts, slalom}: {attempts: Attempt[], slalom: boolean}) {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const number = (value: number) => formatNumber(value, locale)
  const time = formatTime
  const content = attempts.map(a => <Box key={a.number}>
    <Typography>{t('resultGrid.attempt')} {number(a.number)}: {t(`results.${a.status}`)} · {t('resultGrid.time')}: {time(a.durationCentiseconds)} · {t('resultGrid.penalty')}: {time(a.gates.length ? a.gates.reduce((sum, gate) => sum + gate.penaltySeconds * 100, 0) : a.penaltyCentiseconds)}</Typography>
    {a.gates.length > 0 && <Typography>{a.gates.map(g => `${t('resultGrid.gate')} ${number(g.number)}: ${number(g.penaltySeconds)} s`).join(' · ')}</Typography>}
  </Box>)
  return slalom ? <details><summary>{t('results.attemptDetails')}</summary>{content}</details> : <>{content}</>
}

function OverallBreakdown({row, disciplines}: {row: Result, disciplines: Discipline[]}) {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const number = (value: number | null | undefined) => value == null ? '—' : formatNumber(value, locale)
  return <>
    <Typography>{t('results.status')}: {t(`results.${overallStatus(row)}`)}</Typography>
    <Typography>{t('results.points')}: {number(row.totalPoints)}</Typography>
    {row.appliedTiebreakDisciplineId != null && <Typography>{t('results.appliedTiebreak')}: {(() => {const d = disciplines.find(item => item.id === row.appliedTiebreakDisciplineId); return d ? d.name || t(`discipline.types.${d.type}` as 'discipline.types.SPRINT') : '—'})()}</Typography>}
    <Typography>{t('results.breakdown')}</Typography>
    <Box component="ul">{disciplines.map(d => {
      const part = row.disciplines?.find(p => p.disciplineId === d.id)
      return <li key={d.id}>{d.name || t(`discipline.types.${d.type}` as 'discipline.types.SPRINT')}: {part ? <>{t(`results.${part.status}`)} · {t('results.place')}: {number(part.place)} · {t('results.points')}: {number(part.points)}</> : t('results.missing')}</li>
    })}</Box>
  </>
}

function overallStatus(row: Result) {
  return row.disqualified ? 'DSQ_COMPETITION' : row.place == null ? 'NO_FINISH' : 'FINISHED'
}
