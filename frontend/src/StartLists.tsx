import {useEffect, useState} from 'react'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'
import {wallTimeToInstant} from './i18n/wall-time'

type Competition = {id: number, title: string, timeZone: string}
type Discipline = {id: number, name: string, categoryIds: number[]}
type Category = {id: number, displayName: string}
type Crew = {id: number, categoryId: number, crewName: string, startNumber: number, eligible: boolean}
type Slot = {crewId: number, crewName: string, startNumber: number, position: number, plannedTime: string | null}
type StartList = {id: number | null, slots: Slot[], locked: boolean}
export default function StartLists({readOnly}: {readOnly: boolean}) {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const [competition, setCompetition] = useState(params.get('competition') ?? '')
  const [discipline, setDiscipline] = useState(params.get('discipline') ?? '')
  const [category, setCategory] = useState('')
  const [competitions, setCompetitions] = useState<Competition[]>([])
  const [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [crews, setCrews] = useState<Crew[]>([])
  const [list, setList] = useState<StartList | null>(null)
  const [mode, setMode] = useState('random')
  const [first, setFirst] = useState('')
  const [interval, setInterval] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const [drag, setDrag] = useState<number | null>(null)
  const base = `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}/start-list`
  useEffect(() => {
    let active = true
    const load = async () => {
      const firstPage = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
      const pages = await Promise.all(Array.from({length: Math.max(0, firstPage.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
      if (active) setCompetitions([...firstPage.content, ...pages.flatMap(page => page.content)])
    }
    void load().catch(e => {if (active) setError(apiErrorKey(e))})
    return () => {active = false}
  }, [])
  useEffect(() => {
    if (!competition) return
    let active = true
    Promise.all([apiRequest<Discipline[]>(`/api/competitions/${competition}/disciplines`), apiRequest<Category[]>(`/api/competitions/${competition}/categories`), readOnly ? Promise.resolve([] as Crew[]) : apiRequest<Crew[]>(`/api/competitions/${competition}/entries/private`)])
      .then(([ds, cs, entries]) => {if (active) {setDisciplines(ds); setCategories(cs); setCrews(entries)}})
      .catch(e => {if (active) setError(apiErrorKey(e))})
    return () => {active = false}
  }, [competition, readOnly])
  useEffect(() => {
    if (!competition || !discipline || !category) return
    let active = true
    apiRequest<StartList>(base).then(value => {if (active) setList(value)}).catch(e => {if (active) setError(apiErrorKey(e))})
    return () => {active = false}
  }, [base, competition, discipline, category])
  const eligible = crews.filter(c => c.categoryId === Number(category) && c.eligible)
  const ids = eligible.map(c => c.id)
  const existing = list?.slots.map(s => s.crewId).filter(id => ids.includes(id)) ?? []
  const orderKey = JSON.stringify([category, ids, existing, list?.id])
  const [manualOrder, setManualOrder] = useState<{key: string, value: number[]} | null>(null)
  const order = manualOrder?.key === orderKey ? manualOrder.value : [...existing, ...ids.filter(id => !existing.includes(id))]
  const setOrder = (value: number[]) => setManualOrder({key: orderKey, value})
  const move = (from: number, to: number) => {
    if (to < 0 || to >= order.length || from === to) return
    const next = [...order]; const [item] = next.splice(from, 1); next.splice(to, 0, item); setOrder(next)
  }
  const zone = competitions.find(c => String(c.id) === competition)?.timeZone
  const firstInstant = first && zone ? wallTimeToInstant(first, zone) : null
  const validInterval = /^\d+$/.test(interval) && Number.isSafeInteger(Number(interval))
  const generate = async () => {
    if (!list || busy || list.locked) return
    if (!zone || (!!first !== !!interval) || (first && (!firstInstant || !validInterval))) {setError('invalid'); return}
    if (list.id && !window.confirm(t('startList.regenerate'))) return
    setBusy(true); setError('')
    try {
      const result = await csrfRequest<StartList>(base, 'PUT', {
        random: mode === 'random', reverseOverall: mode === 'reverse',
        crewIds: mode === 'manual' ? order : null,
        firstStart: firstInstant?.toISOString() ?? null, intervalSeconds: interval ? Number(interval) : null,
      })
      setManualOrder(null); setList(result)
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setList({...list, locked: true})
        try {setList(await apiRequest<StartList>(base)); setManualOrder(null); setError(apiErrorKey(e))} catch (error) {setError(apiErrorKey(error))}
      }
      else setError(apiErrorKey(e))
    } finally {setBusy(false)}
  }
  const clear = async () => {
    if (busy || !window.confirm(t('startList.confirmClear'))) return
    setBusy(true); setError('')
    try {
      await csrfRequest(`${base}/source-results`, 'DELETE')
      setList(await apiRequest<StartList>(base))
    }
    catch (error) {setError(apiErrorKey(error))} finally {setBusy(false)}
  }
  const selected = disciplines.find(d => String(d.id) === discipline)
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    <TextField select disabled={busy} label={t('startList.competition')} value={competition} onChange={e => {setManualOrder(null); setCompetition(e.target.value); setDiscipline(''); setCategory(''); setList(null); setDisciplines([]); setCategories([]); setCrews([])}}>
      <MenuItem value="">—</MenuItem>
      {competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>
    {competition && <TextField select disabled={busy} label={t('startList.discipline')} value={discipline} onChange={e => {setManualOrder(null); setDiscipline(e.target.value); setCategory(''); setList(null)}}>
      <MenuItem value="">—</MenuItem>
      {disciplines.map(d => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}</TextField>
    }
    {discipline && <TextField select disabled={busy} label={t('startList.category')} value={category} onChange={e => {setManualOrder(null); setCategory(e.target.value); setList(null)}}>
      <MenuItem value="">—</MenuItem>
      {categories.filter(c => selected?.categoryIds.includes(c.id)).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>
    }
    {error && <Typography role="alert">{t(error === 'invalid' ? 'startList.invalid' : error as 'apiError.network')}</Typography>}
    {list && <>
      {list.locked && <Typography role="status">{t('startList.locked')}</Typography>}
      {!list.id ? <Typography>{t('startList.empty')}</Typography> : <Box component="table">
        <thead><tr><th>{t('startList.position')}</th>
          <th>{t('startList.number')}</th>
          <th>{t('startList.crew')}</th>
          <th>{t('startList.category')}</th>
          <th>{t('startList.planned')} ({zone})</th></tr></thead>
        <tbody>{list.slots.map(slot => <tr key={slot.crewId}><td>{slot.position}</td>
          <td>{slot.startNumber}</td>
          <td>{slot.crewName}</td>
          <td>{categories.find(c => String(c.id) === category)?.displayName}</td>
          <td>{slot.plannedTime ? formatDate(new Date(slot.plannedTime), locale, zone ?? 'UTC', {dateStyle: 'medium', timeStyle: 'medium'}) : '—'}</td></tr>)}</tbody>
      </Box>}
      {!readOnly && <>
        {list.locked && <Button disabled={busy} onClick={() => void clear()}>{t('startList.clear')}</Button>}
        <TextField select label={t('startList.mode')} value={mode} disabled={busy || list.locked} onChange={e => setMode(e.target.value)}>
      <MenuItem value="random">{t('startList.random')}</MenuItem>
      <MenuItem value="manual">{t('startList.manual')}</MenuItem>
      <MenuItem value="reverse">{t('startList.reverse')}</MenuItem></TextField>
        {mode === 'manual' && <Box component="ol">
          {order.map((id, index) => {
            const crew = eligible.find(c => c.id === id)
            const planned = firstInstant && validInterval ? new Date(firstInstant.getTime() + index * Number(interval) * 1000) : null
            return <li key={id} draggable={!list.locked && !busy}
              onDragStart={() => setDrag(index)} onDragOver={e => e.preventDefault()}
              onDrop={e => {e.preventDefault(); if (drag !== null) move(drag, index); setDrag(null)}}>
              {t('startList.position')}: {index + 1} — {t('startList.category')}: {categories.find(c => String(c.id) === category)?.displayName}
              {' — '}{t('startList.number')}: {crew?.startNumber} — {t('startList.crew')}: {crew?.crewName}
              {' — '}{t('startList.planned')}: {planned && Number.isFinite(planned.getTime()) ? formatDate(planned, locale, zone ?? 'UTC', {dateStyle: 'medium', timeStyle: 'medium'}) : '—'}
              <Button disabled={busy || list.locked || index === 0}
                aria-label={`${t('startList.up')}: ${crew?.crewName}`} onClick={() => move(index, index - 1)}>{t('startList.up')}</Button>
              <Button disabled={busy || list.locked || index === order.length - 1}
                aria-label={`${t('startList.down')}: ${crew?.crewName}`} onClick={() => move(index, index + 1)}>{t('startList.down')}</Button>
            </li>
          })}
        </Box>}
        <TextField type="datetime-local" label={`${t('startList.first')} (${zone ?? ''})`} value={first} disabled={busy || list.locked} slotProps={{inputLabel: {shrink: true}}} onChange={e => setFirst(e.target.value)}/>
        <Typography variant="body2">{t('startList.timePolicy')}</Typography>
        <TextField type="number" label={t('startList.interval')} value={interval} disabled={busy || list.locked} slotProps={{htmlInput: {min: 0, step: 1}}} onChange={e => setInterval(e.target.value)}/>
        <Button disabled={busy || list.locked} onClick={() => void generate()}>{t('startList.generate')}</Button>
      </>}
    </>}
  </Box>
}
