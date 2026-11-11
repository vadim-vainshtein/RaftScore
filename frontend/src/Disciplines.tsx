import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'

type Competition = {id: number, title: string}
type Category = {id: number, displayName: string}
type DisciplineType = 'SPRINT' | 'PARALLEL_SPRINT' | 'SLALOM' | 'LONG_RACE'
const types: DisciplineType[] = ['SPRINT', 'PARALLEL_SPRINT', 'SLALOM', 'LONG_RACE']
type Discipline = {id: number, name: string, type: DisciplineType, displayOrder: number, categoryIds: number[], gateCount: number | null, attemptCount: number | null, qualificationDisciplineId: number | null, pointsTableId: number | null, version: number, resultsEntered: boolean}
type Draft = Omit<Discipline, 'id' | 'pointsTableId' | 'version' | 'resultsEntered'> & {editing?: Discipline}
const blank = (): Draft => ({name: '', type: 'SPRINT', displayOrder: 0, categoryIds: [], gateCount: null, attemptCount: null, qualificationDisciplineId: null})

export default function Disciplines({readOnly}: {readOnly: boolean}) {
  const {t} = useTranslation()
  const [competitions, setCompetitions] = useState<Competition[]>([]), [competition, setCompetition] = useState('')
  const [tables, setTables] = useState<{id: number, name: string | null}[]>([])
  const [categories, setCategories] = useState<Category[]>([]), [items, setItems] = useState<Discipline[]>([])
  const [draft, setDraft] = useState<Draft | null>(null), [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const base = `/api/competitions/${competition}/disciplines`
  useEffect(() => { let active = true; const load = async () => {
    const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
    const pages = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
    if (active) setCompetitions([...first.content, ...pages.flatMap(p => p.content)])
  }; void load().catch(() => {if (active) setError(t('discipline.error'))}); return () => {active = false} }, [t])
  useEffect(() => { if (!competition) return; let active = true
    Promise.all([apiRequest<Category[]>(`/api/competitions/${competition}/categories`), apiRequest<Discipline[]>(base), apiRequest<{id: number, name: string | null}[]>(`${base}/points-tables`)])
      .then(([cats, list, points]) => {if (active) {setCategories(cats); setItems(list); setTables(points)}}).catch(() => {if (active) setError(t('discipline.error'))})
    return () => {active = false}
  }, [competition, base, revision, t])
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!draft || busy) return
    setBusy(true); setError('')
    try {
      const {editing, ...form} = draft
      await csrfRequest(editing ? `${base}/${editing.id}/configuration` : base, editing ? 'PUT' : 'POST', {...form, ...(editing ? {version: editing.version} : {})})
      setDraft(null); setRevision(v => v + 1)
    } catch (e) {setError(e instanceof ApiError && e.status === 409 ? t('discipline.conflict') : t('discipline.error'))} finally {setBusy(false)}
  }
  const setPoints = async (item: Discipline, value: string) => {
    const selected = value ? Number(value) : null
    if (selected === item.pointsTableId || (item.resultsEntered && !window.confirm(t('discipline.confirmPoints')))) return
    setBusy(true); setError('')
    try { await csrfRequest(`${base}/${item.id}`, 'PUT', {displayOrder: item.displayOrder, pointsTableId: selected, version: item.version}); setRevision(v => v + 1) }
    catch (e) { setError(e instanceof ApiError && e.status === 409 ? t('discipline.conflict') : t('discipline.error')) } finally {setBusy(false)}
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    <TextField select label={t('discipline.competition')} value={competition} disabled={busy} onChange={e => {setCompetition(e.target.value); setItems([]); setCategories([]); setDraft(null); setError('')}}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.title}</MenuItem>)}</TextField>
    {error && <Typography role="alert">{error}</Typography>}
    {competition && <>
      {!readOnly && <Button disabled={busy} onClick={() => setDraft(blank())}>{t('discipline.create')}</Button>}
      {items.map(item => <Box key={item.id}><Typography>{item.displayOrder} — {item.name} — {t(`discipline.types.${item.type}`)} — {item.categoryIds.map(id => categories.find(c => c.id === id)?.displayName).filter(Boolean).join(', ')} — {t(item.resultsEntered ? 'discipline.resultsEntered' : 'discipline.noResults')}{item.type !== 'PARALLEL_SPRINT' && <> — {t('discipline.attemptCount')}: {item.type === 'SLALOM' ? item.attemptCount ?? '—' : 1}</>}</Typography><Button component="a" href={`#/start-lists?competition=${competition}&discipline=${item.id}`}>{t('navigation.startLists')}</Button><Button component="a" href={`#/results?competition=${competition}&discipline=${item.id}`}>{t('navigation.results')}</Button>{!readOnly && item.type !== 'PARALLEL_SPRINT' && <Button component="a" href={`#/result-grid?competition=${competition}&discipline=${item.id}`}>{t('navigation.resultGrid')}</Button>}{!readOnly && <TextField select size="small" label={t('discipline.points')} value={item.pointsTableId ?? ''} disabled={busy || !!draft} onChange={e => void setPoints(item, e.target.value)}><MenuItem value="">{t('discipline.noPoints')}</MenuItem>{tables.map(table => <MenuItem key={table.id} value={table.id}>{table.name || `#${table.id}`}</MenuItem>)}</TextField>}{!readOnly && <Button disabled={busy} onClick={() => setDraft({...item, editing: item})}>{t('discipline.edit')}</Button>}</Box>)}
      {draft && <Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2, maxWidth: 500}}>
        <TextField required label={t('discipline.name')} value={draft.name} onChange={e => setDraft({...draft, name: e.target.value})}/>
        <TextField select disabled={!!draft.editing} label={t('discipline.type')} value={draft.type} onChange={e => {const type = e.target.value as DisciplineType; setDraft({...draft, type, gateCount: type === 'SLALOM' ? 1 : null, attemptCount: type === 'SLALOM' ? 2 : null, qualificationDisciplineId: null})}}>{types.map(type => <MenuItem key={type} value={type}>{t(`discipline.types.${type}`)}</MenuItem>)}</TextField>
        <TextField required type="number" label={t('discipline.order')} value={draft.displayOrder} slotProps={{htmlInput: {min: 0, step: 1}}} onChange={e => setDraft({...draft, displayOrder: Number(e.target.value)})}/>
        <Box component="fieldset"><legend>{t('discipline.categories')}</legend>{categories.map(c => <FormControlLabel key={c.id} label={c.displayName} control={<Checkbox checked={draft.categoryIds.includes(c.id)} onChange={e => {const categoryIds = e.target.checked ? [...draft.categoryIds, c.id] : draft.categoryIds.filter(id => id !== c.id); const qualifier = items.find(item => item.id === draft.qualificationDisciplineId); setDraft({...draft, categoryIds, qualificationDisciplineId: qualifier && categoryIds.every(id => qualifier.categoryIds.includes(id)) ? qualifier.id : null})}}/>}/>)}</Box>
        {draft.type === 'SLALOM' && <>{(['gateCount', 'attemptCount'] as const).map(key => <TextField key={key} required type="number" label={t(`discipline.${key}`)} value={draft[key] ?? ''} slotProps={{htmlInput: {min: 1, step: 1}}} onChange={e => setDraft({...draft, [key]: Number(e.target.value)})}/>)}</>}
        {draft.type === 'PARALLEL_SPRINT' && <TextField select label={t('discipline.qualification')} slotProps={{select: {displayEmpty: true}, inputLabel: {shrink: true}}} value={draft.qualificationDisciplineId ?? ''} onChange={e => setDraft({...draft, qualificationDisciplineId: e.target.value ? Number(e.target.value) : null})}><MenuItem value="">{t('discipline.automatic')}</MenuItem>{items.filter(d => d.id !== draft.editing?.id && d.type !== 'PARALLEL_SPRINT' && draft.categoryIds.every(id => d.categoryIds.includes(id))).map(d => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField>}
        <Box><Button type="submit" disabled={busy}>{t('discipline.save')}</Button><Button disabled={busy} onClick={() => setDraft(null)}>{t('discipline.cancel')}</Button></Box>
      </Box>}
    </>}
  </Box>
}
