import {useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'
import CompetitionWizard from './CompetitionWizard'
import TimeZoneField from './TimeZoneField'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Item = {id: number, title: string, startDate: string, endDate: string, location: string, state: 'CREATED' | 'STARTED' | 'COMPLETED', categoryCount: number, crewCount: number}
type Field = 'representative' | 'phone' | 'whatsapp' | 'telegram' | 'email'
type Policy = 'HIDDEN' | 'OPTIONAL' | 'REQUIRED'
const defaultFields: Record<Field, Policy> = {representative: 'REQUIRED', phone: 'OPTIONAL', whatsapp: 'OPTIONAL', telegram: 'OPTIONAL', email: 'OPTIONAL'}
type Details = {id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean, status: 'CREATED' | 'STARTED' | 'COMPLETED', representativeFields?: Record<Field, Policy>}
type Draft = Omit<Details, 'id' | 'version' | 'status'> & {id?: number, version?: number}
type Page = {content: Item[], page: number, totalPages: number, totalElements: number}

export default function Competitions({readOnly, username, workspaceEditId}: {readOnly: boolean, username?: string, workspaceEditId?: string}) {
  const {t, i18n} = useTranslation()
  const draftKey = username ? `raftscore.wizard.${username}` : undefined
  const contextParams = new URLSearchParams()
  const [sourceHash] = useState(() => window.location.hash)
  const routeParams = new URLSearchParams(sourceHash.split('?')[1] ?? '')
  if (workspaceEditId) {routeParams.set('edit', workspaceEditId); routeParams.set('fromWorkspace', '1')}
  for (const key of ['discipline', 'category']) {const value = routeParams.get(key); if (value) contextParams.set(key, value)}
  const workspaceQuery = contextParams.size ? '?' + contextParams : ''
  const returnToWorkspace = routeParams.get('fromWorkspace') === '1'
  const completionParams = new URLSearchParams(contextParams)
  completionParams.set('complete', '1')
  const displayDate = (value: string | null) => value ? formatDate(new Date(`${value}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC') : '—'
  const [search, setSearch] = useState(''), [sort, setSort] = useState('ASC'), [page, setPage] = useState(0)
  const [result, setResult] = useState<Page | null>(null), [revision, setRevision] = useState(0)
  const [details, setDetails] = useState<Details | null>(null), [draft, setDraft] = useState<Draft | null>(null)
  const originalDraft = useRef<string | null>(null)
  const editCompetition = (item: Details) => {originalDraft.current = JSON.stringify(item); setDraft(item)}
  const [loadingDetails, setLoadingDetails] = useState(() => /^\d+$/.test(routeParams.get('edit') ?? routeParams.get('view') ?? ''))
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [creating, setCreating] = useState(false)
  useEffect(() => {if (!workspaceEditId) document.title = [creating ? t('competition.create') : draft ? t('competition.edit') : t('navigation.competitions'), draft?.title || details?.title, 'RaftScore'].filter(Boolean).join(' — ')}, [workspaceEditId, creating, draft, details?.title, t])
  useEffect(() => { if (workspaceEditId) return; let active = true
    const query = new URLSearchParams({search, sort, page: String(page), size: '20'})
    apiRequest<Page>(`/api/competitions?${query}`).then(value => {if (!Array.isArray(value.content)) throw new Error('Invalid competition page'); if (active) {setResult(value); setError('')}}).catch(e => {if (active) {setResult(null); setError(apiErrorKey(e))}})
    return () => {active = false}
  }, [search, sort, page, revision, t, workspaceEditId])
  useEffect(() => {const params = new URLSearchParams(window.location.hash.split('?')[1] ?? ''); if (workspaceEditId) params.set('edit', workspaceEditId); const id = params.get('edit') ?? params.get('view'); if (id && /^\d+$/.test(id)) apiRequest<Details>(`/api/competitions/${id}`).then(item => {if (params.has('edit') && !readOnly && item.status !== 'COMPLETED') editCompetition(item); else setDetails(item)}).catch(e => setError(apiErrorKey(e))).finally(() => setLoadingDetails(false))}, [readOnly, workspaceEditId])
  const save = async (event: FormEvent) => {event.preventDefault(); if (!draft || busy) return
    setBusy(true); setError('')
    try {const {id, ...body} = draft; delete (body as Draft & {status?: string}).status; const saved = await csrfRequest<Details>(id ? `/api/competitions/${id}` : '/api/competitions', id ? 'PUT' : 'POST', {...body, endDate: body.endDate || null}); setDraft(null); if (returnToWorkspace && id && window.location.hash === sourceHash) window.location.hash = `/workspace/${id}${workspaceQuery}`; else setDetails(saved); setRevision(v => v + 1)}
    catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    {loadingDetails ? null : creating ? <CompetitionWizard draftKey={draftKey} onCancel={() => {if (window.confirm(t('wizard.discardDraft'))) {if (draftKey) localStorage.removeItem(draftKey); setCreating(false)}}} onSaved={saved => {setCreating(false); window.location.hash = `/workspace/${saved.id}`; setRevision(v => v + 1)}}/> : draft ? <><Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2, maxWidth: 500}}>
      <TextField required label={t('competition.title')} value={draft.title} onChange={e => setDraft({...draft, title: e.target.value})}/>
      <TextField required type="date" label={t('competition.startDate')} slotProps={{inputLabel: {shrink: true}}} value={draft.startDate} onChange={e => setDraft({...draft, startDate: e.target.value})}/>
      <TextField type="date" label={t('competition.endDate')} slotProps={{inputLabel: {shrink: true}, htmlInput: {min: draft.startDate}}} value={draft.endDate} onChange={e => setDraft({...draft, endDate: e.target.value})}/>
      <TextField required label={t('competition.location')} value={draft.location} onChange={e => setDraft({...draft, location: e.target.value})}/>
      <TimeZoneField value={draft.timeZone} date={draft.startDate} onChange={timeZone => setDraft({...draft, timeZone})}/>
      <TextField label={t('competition.description')} multiline value={draft.description ?? ''} onChange={e => setDraft({...draft, description: e.target.value})}/>
      <FormControlLabel label={t('competition.admission')} control={<Checkbox checked={draft.admissionConfirmationRequired} onChange={e => setDraft({...draft, admissionConfirmationRequired: e.target.checked})}/>}/>
      <Typography>{t('crew.representativeSettings')}</Typography>
      {(['representative', 'phone', 'whatsapp', 'telegram', 'email'] as const).map(field => <TextField key={field} select label={t(`crew.${field}`)} value={(draft.representativeFields ?? defaultFields)[field]} onChange={e => setDraft({...draft, representativeFields: {...(draft.representativeFields ?? defaultFields), [field]: e.target.value as Policy}})}>{(['HIDDEN', 'OPTIONAL', 'REQUIRED'] as const).map(policy => <MenuItem key={policy} value={policy}>{t(`crew.policy.${policy}`)}</MenuItem>)}</TextField>)}
      {draft.id && <Button component="a" href={`#/workspace/${draft.id}/disciplines?${new URLSearchParams({...Object.fromEntries(contextParams), editDisciplines: '1'})}`} onClick={event => {if (busy || (JSON.stringify(draft) !== originalDraft.current && !window.confirm(t('competition.discardChanges')))) event.preventDefault()}}>{t('navigation.disciplines')}</Button>}
      <Box><Button type="submit" disabled={busy}>{t('competition.save')}</Button><Button disabled={busy} onClick={() => {if (returnToWorkspace && draft.id) window.location.hash = `/workspace/${draft.id}${workspaceQuery}`; else setDraft(null)}}>{t('competition.cancel')}</Button></Box>
    </Box>{draft.id && <CompetitionCategorySeats competitionId={draft.id}/>}</> : workspaceEditId ? <Button component="a" href={`#/workspace/${workspaceEditId}${workspaceQuery}`}>{t('workspace.overview')}</Button> : details ? <Box sx={{display: 'grid', gap: 1}}>
      <Typography variant="h5">{details.title}</Typography><Typography>{displayDate(details.startDate)} – {displayDate(details.endDate)} · {details.location} · {details.timeZone}</Typography>
      {details.description && <Typography>{details.description}</Typography>}
      <Typography>{t('competition.admission')}: {t(details.admissionConfirmationRequired ? 'competition.yes' : 'competition.no')}</Typography>
      {!readOnly && details.status !== 'COMPLETED' && <CompetitionCategorySeats competitionId={details.id}/>}
      <Typography>{t(`competition.states.${details.status}`)}</Typography>
      <Box><Button component="a" href={`#/workspace/${details.id}${workspaceQuery}`}>{t('competition.workspace')}</Button>{!readOnly && details.status === 'STARTED' && <Button component="a" href={`#/workspace/${details.id}?${completionParams}`}>{t('competition.complete')}</Button>}{!readOnly && details.status === 'CREATED' && <Button disabled={busy} onClick={async () => {setBusy(true); try {setDetails(await csrfRequest<Details>(`/api/competitions/${details.id}/status`, 'PUT', {status: 'STARTED', version: details.version})); setRevision(v => v + 1)} catch(e) {setError(apiErrorKey(e))} finally {setBusy(false)}}}>{t('competition.start')}</Button>}{!readOnly && details.status !== 'COMPLETED' && <Button disabled={busy} onClick={() => editCompetition(details)}>{t('competition.edit')}</Button>}<Button onClick={() => setDetails(null)}>{t('competition.back')}</Button></Box>
    </Box> : <>
      <Box className="competition-toolbar"><TextField label={t('competition.search')} value={search} onChange={e => {setSearch(e.target.value); setPage(0)}}/><TextField select label={t('competition.sort')} value={sort} onChange={e => {setSort(e.target.value); setPage(0)}}><MenuItem value="ASC">{t('competition.ascending')}</MenuItem><MenuItem value="DESC">{t('competition.descending')}</MenuItem></TextField>{!readOnly && <Button variant="contained" onClick={() => setCreating(true)}>{draftKey && localStorage.getItem(draftKey) ? t('wizard.continueDraft') : t('competition.create')}</Button>}</Box>
      {result && <><Typography color="text.secondary" variant="body2">{t('competition.total', {count: result.totalElements})}</Typography><Box className="competition-list">{result.content.map(item => <Box component="article" className="competition-card" key={item.id}>
<Box className="competition-card-content"><Box className="competition-card-heading"><Typography component="h2" variant="h6"><a href={`#/workspace/${item.id}`}>{item.title}</a></Typography><Box component="span" className={`status-pill status-${item.state.toLowerCase()}`}>{t(`competition.states.${item.state}`)}</Box></Box><Box className="competition-meta"><Typography>{displayDate(item.startDate)}{item.endDate && item.endDate !== item.startDate ? ` – ${displayDate(item.endDate)}` : ''}</Typography><Typography>{item.location}</Typography><Typography>{t('competition.counts', {categories: item.categoryCount, crews: item.crewCount})}</Typography></Box></Box>
</Box>)}</Box>{result.totalPages > 1 && <Box><Button disabled={page === 0} onClick={() => setPage(page - 1)}>{t('competition.previous')}</Button><Typography component="span">{page + 1} / {Math.max(1, result.totalPages)}</Typography><Button disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>{t('competition.next')}</Button></Box>}</>}
    </>}
  </Box>
}

function CompetitionCategorySeats({competitionId}: {competitionId: number}) {
  const {t} = useTranslation()
  const [categories, setCategories] = useState<{id: number, displayName: string, crewSize: number, version: number, seatLabels: string[]}[]>([])
  const [categoriesLoaded, setCategoriesLoaded] = useState(false)
  const [templates, setTemplates] = useState<{id: number, displayName: string, archived: boolean}[]>([]), [templateId, setTemplateId] = useState('')
  const [editing, setEditing] = useState<number | null>(null), [labels, setLabels] = useState<string[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  useEffect(() => {let active = true; apiRequest<typeof categories>(`/api/competitions/${competitionId}/categories`).then(result => {if (!Array.isArray(result)) throw new Error('Invalid categories'); if (active) {setCategories(result); setCategoriesLoaded(true)}}).catch(e => {if (active) setError(apiErrorKey(e))}); return () => {active = false}}, [competitionId])
  useEffect(() => {
    let active = true
    const loadTemplates = async () => {
      try {
        let page = 0, total = 1
        const all: typeof templates = []
        while (page < total) {
          const result = await apiRequest<{content: typeof templates, totalPages: number}>(`/api/category-templates?archived=false&page=${page}&size=100`)
          all.push(...result.content.filter(template => !template.archived))
          total = result.totalPages
          page++
        }
        if (active) setTemplates(all)
      } catch {if (active) setError('wizard.templatesError')}
    }
    void loadTemplates()
    return () => {active = false}
  }, [])
  const add = async () => {
    if (!templateId) return
    setBusy(true); setError('')
    try {
      const created = await csrfRequest<typeof categories[number]>(`/api/competitions/${competitionId}/categories`, 'POST', {templateId: Number(templateId)})
      setCategories(current => [...current, created]); setTemplateId('')
    } catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  const remove = async (category: typeof categories[number]) => {
    if (!window.confirm(t('crew.confirmRemoveCategory', {name: category.displayName}))) return
    setBusy(true); setError('')
    try {
      await csrfRequest(`/api/competitions/${competitionId}/categories/${category.id}`, 'DELETE', {version: category.version})
      setCategories(current => current.filter(c => c.id !== category.id))
      if (editing === category.id) setEditing(null)
    } catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  const item = categories.find(category => category.id === editing)
  const save = async () => {if (!item) return; setBusy(true); setError(''); try {const updated = await csrfRequest<typeof item>(`/api/competitions/${competitionId}/categories/${item.id}/seat-labels`, 'PUT', {version: item.version, seatLabels: labels}); setCategories(current => current.map(c => c.id === updated.id ? updated : c)); setEditing(null)} catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}}
  return <Box>
    <Typography>{t('crew.categorySeats')}</Typography>
    <Typography variant="body2">{t('crew.categoryChangesImmediate')}</Typography>
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    <TextField select label={t('crew.categoryTemplate')} value={templateId} onChange={e => setTemplateId(e.target.value)} sx={{minWidth: 220}}>{templates.map(template => <MenuItem key={template.id} value={String(template.id)}>{template.displayName}</MenuItem>)}</TextField>
    <Button disabled={busy || !categoriesLoaded || !templateId} onClick={() => void add()}>{t('crew.addCategory')}</Button>
    {categories.map(category => <Box key={category.id}>
      <Typography>{category.displayName}</Typography>
      <Button disabled={busy} onClick={() => {setEditing(category.id); setLabels(category.seatLabels ?? [])}}>{t('crew.editCategory')}</Button>
      <Button disabled={busy} onClick={() => void remove(category)} aria-label={t('crew.removeCategoryNamed', {name: category.displayName})}>{t('crew.removeCategory')}</Button>
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
