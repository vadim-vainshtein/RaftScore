import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import CompetitionWizard from './CompetitionWizard'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Item = {id: number, title: string, startDate: string, endDate: string, location: string, state: 'UPCOMING' | 'IN_PROGRESS' | 'COMPLETED', categoryCount: number, crewCount: number}
type Details = {id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}
type Draft = Omit<Details, 'id' | 'version'> & {id?: number, version?: number}
type Page = {content: Item[], page: number, totalPages: number, totalElements: number}

export default function Competitions({readOnly}: {readOnly: boolean}) {
  const {t, i18n} = useTranslation()
  const displayDate = (value: string | null) => value ? formatDate(new Date(`${value}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC') : '—'
  const [search, setSearch] = useState(''), [sort, setSort] = useState('ASC'), [page, setPage] = useState(0)
  const [result, setResult] = useState<Page | null>(null), [revision, setRevision] = useState(0)
  const [details, setDetails] = useState<Details | null>(null), [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [creating, setCreating] = useState(false)
  useEffect(() => { let active = true
    const query = new URLSearchParams({search, sort, page: String(page), size: '20'})
    apiRequest<Page>(`/api/competitions?${query}`).then(value => {if (!Array.isArray(value.content)) throw new Error('Invalid competition page'); if (active) {setResult(value); setError('')}}).catch(() => {if (active) {setResult(null); setError(t('competition.error'))}})
    return () => {active = false}
  }, [search, sort, page, revision, t])
  const open = async (id: number) => {setBusy(true); setError(''); try {setDetails(await apiRequest<Details>(`/api/competitions/${id}`))} catch {setError(t('competition.error'))} finally {setBusy(false)}}
  const edit = async (id: number) => {setBusy(true); setError(''); try {setDraft(await apiRequest<Details>(`/api/competitions/${id}`))} catch {setError(t('competition.error'))} finally {setBusy(false)}}
  const save = async (event: FormEvent) => {event.preventDefault(); if (!draft || busy) return
    setBusy(true); setError('')
    try {const {id, ...body} = draft; const saved = await csrfRequest<Details>(id ? `/api/competitions/${id}` : '/api/competitions', id ? 'PUT' : 'POST', {...body, endDate: body.endDate || null}); setDraft(null); setDetails(saved); setRevision(v => v + 1)}
    catch (e) {setError(e instanceof ApiError && e.status === 409 ? t('competition.conflict') : t('competition.error'))} finally {setBusy(false)}
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    {error && <Typography role="alert">{error}</Typography>}
    {creating ? <CompetitionWizard onCancel={() => setCreating(false)} onSaved={saved => {setCreating(false); setDetails(saved); setRevision(v => v + 1)}}/> : draft ? <Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2, maxWidth: 500}}>
      <TextField required label={t('competition.title')} value={draft.title} onChange={e => setDraft({...draft, title: e.target.value})}/>
      <TextField required type="date" label={t('competition.startDate')} slotProps={{inputLabel: {shrink: true}}} value={draft.startDate} onChange={e => setDraft({...draft, startDate: e.target.value})}/>
      <TextField type="date" label={t('competition.endDate')} slotProps={{inputLabel: {shrink: true}, htmlInput: {min: draft.startDate}}} value={draft.endDate} onChange={e => setDraft({...draft, endDate: e.target.value})}/>
      <TextField required label={t('competition.location')} value={draft.location} onChange={e => setDraft({...draft, location: e.target.value})}/>
      <TextField required label={t('competition.timeZone')} value={draft.timeZone} onChange={e => setDraft({...draft, timeZone: e.target.value})}/>
      <TextField label={t('competition.description')} multiline value={draft.description ?? ''} onChange={e => setDraft({...draft, description: e.target.value})}/>
      <FormControlLabel label={t('competition.admission')} control={<Checkbox checked={draft.admissionConfirmationRequired} onChange={e => setDraft({...draft, admissionConfirmationRequired: e.target.checked})}/>}/>
      <Box><Button type="submit" disabled={busy}>{t('competition.save')}</Button><Button disabled={busy} onClick={() => setDraft(null)}>{t('competition.cancel')}</Button></Box>
    </Box> : details ? <Box sx={{display: 'grid', gap: 1}}>
      <Typography variant="h5">{details.title}</Typography><Typography>{displayDate(details.startDate)} – {displayDate(details.endDate)} · {details.location} · {details.timeZone}</Typography>
      {details.description && <Typography>{details.description}</Typography>}
      <Typography>{t('competition.admission')}: {t(details.admissionConfirmationRequired ? 'competition.yes' : 'competition.no')}</Typography>
      <Box>{!readOnly && <Button disabled={busy} onClick={() => setDraft(details)}>{t('competition.edit')}</Button>}<Button onClick={() => setDetails(null)}>{t('competition.back')}</Button></Box>
    </Box> : <>
      <Box sx={{display: 'flex', flexWrap: 'wrap', gap: 2}}><TextField label={t('competition.search')} value={search} onChange={e => {setSearch(e.target.value); setPage(0)}}/><TextField select label={t('competition.sort')} value={sort} onChange={e => {setSort(e.target.value); setPage(0)}}><MenuItem value="ASC">{t('competition.ascending')}</MenuItem><MenuItem value="DESC">{t('competition.descending')}</MenuItem></TextField>{!readOnly && <Button onClick={() => setCreating(true)}>{t('competition.create')}</Button>}</Box>
      {result && <><Typography>{t('competition.total', {count: result.totalElements})}</Typography>{result.content.map(item => <Box key={item.id}><Typography>{item.title} · {displayDate(item.startDate)} – {displayDate(item.endDate)} · {item.location} · {t(`competition.states.${item.state}`)} · {t('competition.counts', {categories: item.categoryCount, crews: item.crewCount})}</Typography><Button disabled={busy} onClick={() => void open(item.id)}>{t('competition.open')}</Button>{!readOnly && <Button disabled={busy} onClick={() => void edit(item.id)}>{t('competition.edit')}</Button>}</Box>)}<Box><Button disabled={page === 0} onClick={() => setPage(page - 1)}>{t('competition.previous')}</Button><Typography component="span">{page + 1} / {Math.max(1, result.totalPages)}</Typography><Button disabled={page + 1 >= result.totalPages} onClick={() => setPage(page + 1)}>{t('competition.next')}</Button></Box></>}
    </>}
  </Box>
}
