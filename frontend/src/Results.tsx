import {useEffect, useRef, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {formatNumber} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Competition = {id: number, title: string}
type Category = {id: number, displayName: string}
type Discipline = {id: number, name: string, type: string, categoryIds: number[]}
type Status = 'FINISHED' | 'DNS' | 'DNF' | 'DSQ_ATTEMPT' | 'DSQ_DISCIPLINE' | 'DSQ_COMPETITION'
type Part = {disciplineId: number, disciplineName: string, place: number | null, status: Status, points: number}
type Attempt = {number: number, durationCentiseconds: number | null, penaltyCentiseconds: number, status: Status, gates: {number: number, penaltySeconds: number}[]}
type Result = {crewId: number, crewName: string, regionOrClub: string, startNumber: number, place: number | null, totalCentiseconds?: number | null, status?: Status, points?: number, totalPoints?: number, disqualified?: boolean, disciplines?: Part[], appliedTiebreakDisciplineId?: number | null, attempts?: Attempt[]}

export default function Results({readOnly}: {readOnly: boolean}) {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(params.get('competition') ?? '')
  const [category, setCategory] = useState(params.get('category') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [rows, setRows] = useState<Result[] | null>(null)
  const [error, setError] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const [busy, setBusy] = useState(false)
  const [filtersVisible, setFiltersVisible] = useState(true)
  const display = useRef<HTMLDivElement>(null)
  const [incomplete, setIncomplete] = useState(false)
  const number = (value: number | null | undefined) => value == null ? '—' : formatNumber(value, locale)
  const time = (value: number | null | undefined) => value == null ? '—' : `${number(Math.floor(value / 6000))}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}${new Intl.NumberFormat(locale).format(1.1).replace(/\d/g, '')}${String(value % 100).padStart(2, '0')}`
  useEffect(() => {
    let active = true
    const load = async () => {
      const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
      const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
      if (active) setCompetitions([...first.content, ...rest.flatMap(page => page.content)])
    }
    void load().catch(() => active && setError(true))
    return () => {active = false}
  }, [])
  useEffect(() => {
    if (!competition) return
    let active = true
    Promise.all([apiRequest<Category[]>(`/api/competitions/${competition}/categories`), apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`)])
      .then(([cs, ds]) => {if (active) {setCategories(cs); setDisciplines(ds)}}).catch(() => active && setError(true))
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
        if (active) {setRows(data); setError(false)}
        const scoped = disciplines.filter(d => d.categoryIds.includes(Number(category)) && (!discipline || String(d.id) === discipline))
        const lists = await Promise.all(scoped.filter(d => d.type !== 'PARALLEL_SPRINT').map(async d => ({
          id: d.id, list: await apiRequest<{slots: {crewId: number}[]}>(
            `/api/competitions/${competition}/disciplines/${d.id}/categories/${category}/start-list`),
        })))
        const missing = lists.some(({id, list}) => list.slots.some(slot => !data.some(row => row.crewId === slot.crewId && (discipline || row.disciplines?.some(part => part.disciplineId === id)))))
        if (active) setIncomplete(missing)
      }
      catch {if (active) setError(true)} finally {pending = false}
    }
    void load()
    const timer = readOnly ? window.setInterval(() => void load(), 10000) : undefined
    return () => {active = false; window.clearInterval(timer)}
  }, [competition, category, discipline, readOnly, refresh, disciplines])
  const selected = disciplines.find(d => String(d.id) === discipline)
  return <Box ref={display} sx={{display: 'grid', gap: 2, mt: 2, bgcolor: 'background.paper', overflow: 'auto'}}>
    <Box><Button onClick={() => setFiltersVisible(value => !value)}>{t(filtersVisible ? 'results.hideFilters' : 'results.showFilters')}</Button><Button onClick={() => {if (document.fullscreenElement) void document.exitFullscreen(); else if (display.current?.requestFullscreen) void display.current.requestFullscreen().catch(() => setError(true))}}>{t('results.fullscreen')}</Button></Box>
    {filtersVisible && <><TextField select label={t('results.competition')} value={competition} onChange={e => {setCompetition(e.target.value); setCategory(''); setDiscipline(''); setRows(null); setCategories([]); setDisciplines([]); setIncomplete(false); setError(false)}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>
    {competition && <TextField select label={t('results.category')} value={category} onChange={e => {setCategory(e.target.value); setRows(null); setError(false)}}><MenuItem value="">—</MenuItem>{categories.filter(c => !discipline || selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {competition && <TextField select label={t('results.discipline')} value={discipline} onChange={e => {setDiscipline(e.target.value); if (e.target.value && !disciplines.find(d => String(d.id) === e.target.value)?.categoryIds.includes(Number(category))) setCategory(''); setRows(null); setError(false)}}><MenuItem value="">{t('results.overall')}</MenuItem>{disciplines.map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}</TextField>}</>}
    {competition && category && <Box><Button onClick={() => setRefresh(value => value + 1)}>{t('results.refresh')}</Button>{discipline && selected?.type === 'PARALLEL_SPRINT' && <Button component="a" href={`#/parallel-sprint?competition=${competition}&discipline=${discipline}&category=${category}`}>{t('navigation.parallelSprint')}</Button>}{!readOnly && <>{discipline && selected?.type !== 'PARALLEL_SPRINT' && <Button component="a" href={`#/result-grid?competition=${competition}&discipline=${discipline}&category=${category}`}>{t('results.edit')}</Button>}<Button disabled={busy} onClick={async () => {setBusy(true); try {await csrfRequest(`/api/competitions/${competition}/recalculate`, 'POST'); setRefresh(value => value + 1)} catch {setError(true)} finally {setBusy(false)}}}>{t('results.recalculate')}</Button></>}</Box>}
    {rows && incomplete && <Typography>{t('results.availableData')}</Typography>}
    {error && <Typography role="alert">{t('results.error')}</Typography>}
    {category && !rows && !error && <Typography>{t('results.loading')}</Typography>}
    {rows && rows.length === 0 && <Typography>{t('results.empty')}</Typography>}
    {rows?.map(row => <Box component="article" key={row.crewId} sx={{border: 1, borderColor: 'divider', borderRadius: 1, p: 2, overflowWrap: 'anywhere'}}>
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
  const time = (value: number | null) => value == null ? '—' : `${number(Math.floor(value / 6000))}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}${new Intl.NumberFormat(locale).format(1.1).replace(/\d/g, '')}${String(value % 100).padStart(2, '0')}`
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
    <Typography>{t('results.status')}: {t(`results.${row.disqualified ? 'DSQ_COMPETITION' : 'FINISHED'}`)}</Typography>
    <Typography>{t('results.points')}: {number(row.totalPoints)}</Typography>
    {row.appliedTiebreakDisciplineId != null && <Typography>{t('results.appliedTiebreak')}: {disciplines.find(d => d.id === row.appliedTiebreakDisciplineId)?.name ?? '—'}</Typography>}
    <Typography>{t('results.breakdown')}</Typography>
    <Box component="ul">{disciplines.map(d => {
      const part = row.disciplines?.find(p => p.disciplineId === d.id)
      return <li key={d.id}>{d.name}: {part ? <>{t(`results.${part.status}`)} · {t('results.place')}: {number(part.place)} · {t('results.points')}: {number(part.points)}</> : t('results.missing')}</li>
    })}</Box>
  </>
}
