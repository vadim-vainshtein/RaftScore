import {useCallback, useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {useTranslation} from 'react-i18next'
import {Box, Button, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography} from '@mui/material'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey, fieldErrorKey} from './api/errors'
type Template = {id: number, classLabel: string, scoringGroupLabel: string, crewSize: number, displayName: string, archived: boolean, version: number}
type Page = {content: Template[], totalPages: number}
type Form = {classLabel: string, scoringGroupLabel: string, crewSize: string}
export type Draft = {form: Form, editing: Template | null, dirty: boolean}
type Fields = Partial<Record<keyof Form, boolean | string>>
const blank: Form = {classLabel: '', scoringGroupLabel: '', crewSize: ''}
const endpoint = '/api/category-templates'
const validLabel = (value: string) => !!value.trim() && !value.includes('\0')
export default function Categories({initialDraft = null, onDraft = () => {}}: {initialDraft?: Draft | null, onDraft?: (draft: Draft | null) => void}) {
  const {t} = useTranslation('categories')
  const {t: common} = useTranslation()
  const [page, setPage] = useState(0), [filter, setFilter] = useState('all'), [revision, setRevision] = useState(0)
  const [list, setList] = useState<{key: string, value?: Page, failed?: boolean} | null>(null)
  const [draft, setDraftState] = useState<Draft | null>(initialDraft)
  const [busy, setBusy] = useState(false), busyRef = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const [fields, setFields] = useState<Fields>({})
  const [lookupRevision, setLookupRevision] = useState(0)
  const [lookup, setLookup] = useState<{key: string, exists?: boolean, failed?: boolean} | null>(null)
  const form = draft?.form, editing = draft?.editing, dirty = draft?.dirty
  const setDraft = (value: Draft | null) => { setDraftState(value); onDraft(value) }
  const fail = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 400 && e.body && typeof e.body === 'object') {
      const entries = (e.body as {fieldErrors?: {field: string, code?: unknown}[]}).fieldErrors
      const next: Fields = {}
      entries?.forEach(({field, code}) => { if (field === 'classLabel' || field === 'scoringGroupLabel' || field === 'crewSize') next[field] = fieldErrorKey(code) })
      setFields(next); setError('invalid'); return
    }
    setError(e instanceof ApiError && e.status === 409 ? 'conflict' : `common:${apiErrorKey(e)}`)
  }, [])
  const listKey = `${page}:${filter}:${revision}`
  useEffect(() => {
    let active = true
    apiRequest<Page>(`${endpoint}?page=${page}&size=20${filter === 'all' ? '' : `&archived=${filter}`}`).then(value => {
      if (!active) return
      if (page > 0 && page >= value.totalPages) { setPage(Math.max(0, value.totalPages - 1)); return }
      setList({key: listKey, value})
    }).catch(e => { if (active) { setList({key: listKey, failed: true}); fail(e) } })
    return () => { active = false }
  }, [page, filter, listKey, fail])
  const lookupKey = form && validLabel(form.classLabel) && validLabel(form.scoringGroupLabel) ? new URLSearchParams({classLabel: form.classLabel, scoringGroupLabel: form.scoringGroupLabel, ...(editing ? {excludeId: String(editing.id)} : {})}).toString() : ''
  useEffect(() => {
    if (!lookupKey) return
    let active = true
    apiRequest<{exists: boolean}>(`${endpoint}/duplicate?${lookupKey}`).then(value => { if (active) setLookup({key: lookupKey, exists: value.exists}) }).catch(() => { if (active) setLookup({key: lookupKey, failed: true}) })
    return () => { active = false }
  }, [lookupKey, lookupRevision])
  const lookupPending = !!lookupKey && lookup?.key !== lookupKey
  useEffect(() => {
    if (!dirty) return
    const handler = (event: BeforeUnloadEvent) => { event.preventDefault() }
    const navigation = (event: Event) => { if (busyRef.current || !window.confirm(t('leave'))) event.preventDefault(); else onDraft(null) }
    window.addEventListener('beforeunload', handler); window.addEventListener('category-navigation', navigation)
    return () => { window.removeEventListener('beforeunload', handler); window.removeEventListener('category-navigation', navigation) }
  }, [dirty, t, onDraft])
  const begin = () => { if (busyRef.current) return false; busyRef.current = true; setBusy(true); return true }
  const end = () => { busyRef.current = false; setBusy(false) }
  const close = () => { if (!busyRef.current && (!dirty || window.confirm(t('leave')))) { setDraft(null); setError(null); setFields({}); setRevision(v => v + 1) } }
  const start = (item?: Template) => {
    if (busyRef.current || (dirty && !window.confirm(t('leave')))) return
    setDraft({editing: item ?? null, form: item ? {classLabel: item.classLabel, scoringGroupLabel: item.scoringGroupLabel, crewSize: String(item.crewSize)} : blank, dirty: false}); setError(null); setFields({})
  }
  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!form || busyRef.current || lookupPending || (!!lookupKey && lookup?.key === lookupKey && lookup.failed)) return
    const size = Number(form.crewSize)
    const invalid: Fields = {classLabel: !validLabel(form.classLabel), scoringGroupLabel: !validLabel(form.scoringGroupLabel), crewSize: !/^\d+$/.test(form.crewSize) || !Number.isInteger(size) || size < 1 || size > 2147483647}
    if (Object.values(invalid).some(Boolean)) { setFields(invalid); setError('invalid'); return }
    if (!begin()) return
    setError(null); setFields({})
    try { await csrfRequest(editing ? `${endpoint}/${editing.id}` : endpoint, editing ? 'PUT' : 'POST', {...form, crewSize: size, ...(editing ? {version: editing.version} : {})}); setDraft(null); setRevision(v => v + 1) }
    catch (e) { fail(e) } finally { end() }
  }
  const toggle = async (item: Template) => {
    if (busyRef.current || form || (!item.archived && !window.confirm(t('confirm'))) || !begin()) return
    setError(null)
    try { await csrfRequest(`${endpoint}/${item.id}/${item.archived ? 'restore' : 'archive'}`, 'PATCH', {version: item.version}); setRevision(v => v + 1) }
    catch (e) { fail(e) } finally { end() }
  }
  const reload = async () => {
    if (!begin()) return
    try {
      if (editing) { const item = await apiRequest<Template>(`${endpoint}/${editing.id}`); setDraft({editing: item, form: {classLabel: item.classLabel, scoringGroupLabel: item.scoringGroupLabel, crewSize: String(item.crewSize)}, dirty: false}) }
      setError(null); setFields({}); setRevision(v => v + 1)
    } catch (e) { fail(e) } finally { end() }
  }
  const current = list?.key === listKey ? list : null
  const duplicate = lookupKey && lookup?.key === lookupKey && lookup.exists
  return <Box sx={{mt: 2}}>
    <Button variant="contained" onClick={() => start()} disabled={busy}>{t('create')}</Button>
    {form && <Box component="form" onSubmit={save} noValidate sx={{display: 'grid', gap: 2, my: 2, maxWidth: 500}}>
      {dirty && <Typography>{t('dirty')}</Typography>}
      {(['classLabel', 'scoringGroupLabel', 'crewSize'] as const).map(key => <TextField key={key} label={t(key === 'classLabel' ? 'class' : key === 'scoringGroupLabel' ? 'group' : 'size')} value={form[key]} disabled={busy} error={!!fields[key]} helperText={fields[key] ? typeof fields[key] === 'string' ? common(fields[key] as 'apiError.required') : t(key === 'crewSize' ? 'sizeError' : 'labelError') : undefined} onChange={e => {if (draft) setDraft({...draft, form: {...form, [key]: e.target.value}, dirty: true})}} required/>)}
      <Typography>{t('preview')}: {form.classLabel} / {form.scoringGroupLabel}</Typography>
      {duplicate && <Typography role="status">{t('duplicate')}</Typography>}{lookup?.key === lookupKey && lookup.failed && <Typography role="alert">{t('lookupFailed')} <Button onClick={() => {setLookup(null); setLookupRevision(v => v + 1)}}>{t('retry')}</Button></Typography>}
      <Box><Button type="submit" disabled={busy || lookupPending || (!!lookupKey && lookup?.key === lookupKey && !!lookup.failed) || error === 'conflict'}>{t('save')}</Button><Button onClick={close} disabled={busy}>{t('cancel')}</Button></Box>
    </Box>}
    {error && <Typography role="alert">{error.startsWith('common:') ? common(error.slice(7) as 'apiError.network') : t(error as 'conflict')}</Typography>}
    {error === 'conflict' && <Box><Button onClick={() => void reload()} disabled={busy}>{t('reload')}</Button><Button onClick={() => {setDraft(null); setError(null); setRevision(v => v + 1)}} disabled={busy}>{t('discard')}</Button></Box>}
    <TextField select label={t('filter')} value={filter} disabled={busy} onChange={e => {setFilter(e.target.value); setPage(0); setError(null)}} sx={{my: 2, minWidth: 160}}><MenuItem value="all">{t('all')}</MenuItem><MenuItem value="false">{t('active')}</MenuItem><MenuItem value="true">{t('archived')}</MenuItem></TextField>
    {current?.value && error !== 'conflict' ? <><Box sx={{overflowX: 'auto'}}><Table><TableHead><TableRow>{(['class','group','display','size','status'] as const).map(key => <TableCell key={key}>{t(key)}</TableCell>)}<TableCell/></TableRow></TableHead><TableBody>
      {current.value.content.map(item => <TableRow key={item.id}><TableCell>{item.classLabel}</TableCell><TableCell>{item.scoringGroupLabel}</TableCell><TableCell>{item.displayName}</TableCell><TableCell>{item.crewSize}</TableCell><TableCell>{t(item.archived ? 'archived' : 'active')}</TableCell><TableCell><Button onClick={() => start(item)} disabled={busy}>{t('edit')}</Button><Button onClick={() => void toggle(item)} disabled={busy || !!form}>{t(item.archived ? 'restore' : 'archive')}</Button></TableCell></TableRow>)}
    </TableBody></Table></Box>{!current.value.content.length && <Typography>{t('empty')}</Typography>}<Button onClick={() => setPage(v => v - 1)} disabled={busy || page === 0}>{t('previous')}</Button>{t('page', {page: page + 1, total: Math.max(1, current.value.totalPages)})}<Button onClick={() => setPage(v => v + 1)} disabled={busy || page + 1 >= current.value.totalPages}>{t('next')}</Button></> : !current?.failed && error !== 'conflict' && <Typography>{t('loading')}</Typography>}
  </Box>
}
