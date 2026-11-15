import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'
import CompetitionWizard from './CompetitionWizard'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Item = {id: number, title: string, startDate: string, endDate: string, location: string, state: 'CREATED' | 'STARTED' | 'COMPLETED', categoryCount: number, crewCount: number}
type Field = 'representative' | 'phone' | 'whatsapp' | 'telegram' | 'email'
type Policy = 'HIDDEN' | 'OPTIONAL' | 'REQUIRED'
const defaultFields: Record<Field, Policy> = {representative: 'REQUIRED', phone: 'OPTIONAL', whatsapp: 'OPTIONAL', telegram: 'OPTIONAL', email: 'OPTIONAL'}
type Details = {id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean, status: 'CREATED' | 'STARTED' | 'COMPLETED', representativeFields?: Record<Field, Policy>}
type Draft = Omit<Details, 'id' | 'version' | 'status'> & {id?: number, version?: number}
type Page = {content: Item[], page: number, totalPages: number, totalElements: number}

export default function Competitions({readOnly, username}: {readOnly: boolean, username?: string}) {
  const {t, i18n} = useTranslation()
  const draftKey = username ? `raftscore.wizard.${username}` : undefined
  const contextParams = new URLSearchParams()
  const routeParams = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  for (const key of ['discipline', 'category']) {const value = routeParams.get(key); if (value) contextParams.set(key, value)}
  const workspaceQuery = contextParams.size ? '?' + contextParams : ''
  const completionParams = new URLSearchParams(contextParams)
  completionParams.set('complete', '1')
  const displayDate = (value: string | null) => value ? formatDate(new Date(`${value}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC') : '—'
  const [search, setSearch] = useState(''), [sort, setSort] = useState('ASC'), [page, setPage] = useState(0)
  const [result, setResult] = useState<Page | null>(null), [revision, setRevision] = useState(0)
  const [details, setDetails] = useState<Details | null>(null), [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [creating, setCreating] = useState(false)
  useEffect(() => { let active = true
    const query = new URLSearchParams({search, sort, page: String(page), size: '20'})
    apiRequest<Page>(`/api/competitions?${query}`).then(value => {if (!Array.isArray(value.content)) throw new Error('Invalid competition page'); if (active) {setResult(value); setError('')}}).catch(e => {if (active) {setResult(null); setError(apiErrorKey(e))}})
    return () => {active = false}
  }, [search, sort, page, revision, t])
  useEffect(() => {const params = new URLSearchParams(window.location.hash.split('?')[1] ?? ''); const id = params.get('edit') ?? params.get('view'); if (id && /^\d+$/.test(id)) apiRequest<Details>(`/api/competitions/${id}`).then(item => {if (params.has('edit') && !readOnly && item.status !== 'COMPLETED') setDraft(item); else setDetails(item)}).catch(e => setError(apiErrorKey(e)))}, [readOnly])
  const save = async (event: FormEvent) => {event.preventDefault(); if (!draft || busy) return
    setBusy(true); setError('')
    try {const {id, ...body} = draft; delete (body as Draft & {status?: string}).status; const saved = await csrfRequest<Details>(id ? `/api/competitions/${id}` : '/api/competitions', id ? 'PUT' : 'POST', {...body, endDate: body.endDate || null}); setDraft(null); setDetails(saved); setRevision(v => v + 1)}
    catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    {creating ? <CompetitionWizard draftKey={draftKey} onCancel={() => {if (window.confirm(t('wizard.discardDraft'))) {if (draftKey) localStorage.removeItem(draftKey); setCreating(false)}}} onSaved={saved => {setCreating(false); window.location.hash = `/workspace/${saved.id}`; setRevision(v => v + 1)}}/> : draft ? <Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2, maxWidth: 500}}>
      <TextField required label={t('competition.title')} value={draft.title} onChange={e => setDraft({...draft, title: e.target.value})}/>
      <TextField required type="date" label={t('competition.startDate')} slotProps={{inputLabel: {shrink: true}}} value={draft.startDate} onChange={e => setDraft({...draft, startDate: e.target.value})}/>
      <TextField type="date" label={t('competition.endDate')} slotProps={{inputLabel: {shrink: true}, htmlInput: {min: draft.startDate}}} value={draft.endDate} onChange={e => setDraft({...draft, endDate: e.target.value})}/>
      <TextField required label={t('competition.location')} value={draft.location} onChange={e => setDraft({...draft, location: e.target.value})}/>
      <TextField required label={t('competition.timeZone')} value={draft.timeZone} onChange={e => setDraft({...draft, timeZone: e.target.value})}/>
      <TextField label={t('competition.description')} multiline value={draft.description ?? ''} onChange={e => setDraft({...draft, description: e.target.value})}/>
      <FormControlLabel label={t('competition.admission')} control={<Checkbox checked={draft.admissionConfirmationRequired} onChange={e => setDraft({...draft, admissionConfirmationRequired: e.target.checked})}/>}/>
      <Typography>{t('crew.representativeSettings')}</Typography>
      {(['representative', 'phone', 'whatsapp', 'telegram', 'email'] as const).map(field => <TextField key={field} select label={t(`crew.${field}`)} value={(draft.representativeFields ?? defaultFields)[field]} onChange={e => setDraft({...draft, representativeFields: {...(draft.representativeFields ?? defaultFields), [field]: e.target.value as Policy}})}>{(['HIDDEN', 'OPTIONAL', 'REQUIRED'] as const).map(policy => <MenuItem key={policy} value={policy}>{t(`crew.policy.${policy}`)}</MenuItem>)}</TextField>)}
      <Box><Button type="submit" disabled={busy}>{t('competition.save')}</Button><Button disabled={busy} onClick={() => setDraft(null)}>{t('competition.cancel')}</Button></Box>
    </Box> : details ? <Box sx={{display: 'grid', gap: 1}}>
      <Typography variant="h5">{details.title}</Typography><Typography>{displayDate(details.startDate)} – {displayDate(details.endDate)} · {details.location} · {details.timeZone}</Typography>
      {details.description && <Typography>{details.description}</Typography>}
      <Typography>{t('competition.admission')}: {t(details.admissionConfirmationRequired ? 'competition.yes' : 'competition.no')}</Typography>
      {!readOnly && details.status !== 'COMPLETED' && <CompetitionCategorySeats competitionId={details.id}/>}
      <Typography>{t(`competition.states.${details.status}`)}</Typography>
      <Box><Button component="a" href={`#/workspace/${details.id}${workspaceQuery}`}>{t('competition.workspace')}</Button>{!readOnly && details.status === 'STARTED' && <Button component="a" href={`#/workspace/${details.id}?${completionParams}`}>{t('competition.complete')}</Button>}{!readOnly && details.status === 'CREATED' && <Button disabled={busy} onClick={async () => {setBusy(true); try {setDetails(await csrfRequest<Details>(`/api/competitions/${details.id}/status`, 'PUT', {status: 'STARTED', version: details.version})); setRevision(v => v + 1)} catch(e) {setError(apiErrorKey(e))} finally {setBusy(false)}}}>{t('competition.start')}</Button>}{!readOnly && details.status !== 'COMPLETED' && <Button disabled={busy} onClick={() => setDraft(details)}>{t('competition.edit')}</Button>}<Button onClick={() => setDetails(null)}>{t('competition.back')}</Button></Box>
    </Box> : <>
      <Box className="competition-toolbar"><TextField label={t('competition.search')} value={search} onChange={e => {setSearch(e.target.value); setPage(0)}}/><TextField select label={t('competition.sort')} value={sort} onChange={e => {setSort(e.target.value); setPage(0)}}><MenuItem value="ASC">{t('competition.ascending')}</MenuItem><MenuItem value="DESC">{t('competition.descending')}</MenuItem></TextField>{!readOnly && <Button variant="contained" onClick={() => setCreating(true)}>{draftKey && localStorage.getItem(draftKey) ? t('wizard.continueDraft') : t('competition.create')}</Button>}</Box>
      {result && <><Typography color="text.secondary" variant="body2">{t('competition.total', {count: result.totalElements})}</Typography><Box className="competition-list">{result.content.map(item => <Box component="article" className="competition-card" key={item.id}>
<Box className="competition-card-content"><Box className="competition-card-heading"><Typography component="h2" variant="h6">{item.title}</Typography><Box component="span" className={`status-pill status-${item.state.toLowerCase()}`}>{t(`competition.states.${item.state}`)}</Box></Box><Box className="competition-meta"><Typography>{displayDate(item.startDate)}{item.endDate && item.endDate !== item.startDate ? ` – ${displayDate(item.endDate)}` : ''}</Typography><Typography>{item.location}</Typography><Typography>{t('competition.counts', {categories: item.categoryCount, crews: item.crewCount})}</Typography></Box></Box>
<Button variant={item.state === 'STARTED' && !readOnly ? 'contained' : 'outlined'} component="a" href={`#/workspace/${item.id}${item.state === 'COMPLETED' || readOnly && item.state === 'STARTED' ? '/results' : item.state === 'STARTED' ? '/result-grid' : ''}`}>{t(item.state === 'CREATED' ? 'competition.prepare' : item.state === 'STARTED' && !readOnly ? 'competition.enterResults' : 'competition.openProtocol')}</Button></Box>)}</Box><Box><Button disabled={page === 0} onClick={() => setPage(page - 1)}>{t('competition.previous')}</Button><Typography component="span">{page + 1} / {Math.max(1, result.totalPages)}</Typography><Button disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>{t('competition.next')}</Button></Box></>}
    </>}
  </Box>
}

function CompetitionCategorySeats({competitionId}: {competitionId: number}) {
  const {t} = useTranslation()
  const [categories, setCategories] = useState<{id: number, displayName: string, crewSize: number, version: number, seatLabels: string[]}[]>([])
  const [editing, setEditing] = useState<number | null>(null), [labels, setLabels] = useState<string[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => {let active = true; apiRequest<typeof categories>(`/api/competitions/${competitionId}/categories`).then(result => {if (active) setCategories(result)}).catch(e => {if (active) setError(apiErrorKey(e))}); return () => {active = false}}, [competitionId])
  const item = categories.find(category => category.id === editing)
  const save = async () => {if (!item) return; setBusy(true); setError(''); try {const updated = await csrfRequest<typeof item>(`/api/competitions/${competitionId}/categories/${item.id}/seat-labels`, 'PUT', {version: item.version, seatLabels: labels}); setCategories(current => current.map(c => c.id === updated.id ? updated : c)); setEditing(null)} catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}}
  return <Box>
    <Typography>{t('crew.categorySeats')}</Typography>
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    {categories.map(category => <Box key={category.id}>
      <Typography>{category.displayName}</Typography>
      <Button disabled={busy} onClick={() => {setEditing(category.id); setLabels(category.seatLabels ?? [])}}>{t('crew.editCategory')}</Button>
    </Box>)}
    {item && <Box component="form" onSubmit={event => {event.preventDefault(); void save()}}>
      <fieldset><legend>{t('crew.editCategory')}: {item.displayName}</legend>
        {Array.from({length: Math.min(item.crewSize, Math.max(8, labels.length))}, (_, i) => <TextField key={i} label={t('crew.seat', {number: i + 1})} value={labels[i] ?? ''} onChange={e => setLabels(Array.from({length: Math.max(i + 1, labels.length)}, (_, n) => n === i ? e.target.value : labels[n] ?? ''))}/>)}
        <Button disabled={busy || Math.max(8, labels.length) >= item.crewSize} onClick={() => setLabels(Array.from({length: Math.max(8, labels.length) + 1}, (_, i) => labels[i] ?? ''))}>{t('crew.addSeat')}</Button>
      </fieldset>
      <Button type="submit" disabled={busy}>{t('crew.save')}</Button><Button disabled={busy} onClick={() => setEditing(null)}>{t('crew.cancel')}</Button>
    </Box>}
  </Box>
}
