import {useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {useTranslation} from 'react-i18next'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'

type Competition = {id: number, title: string, admissionConfirmationRequired: boolean}
type Category = {id: number, displayName: string, crewSize: number}
type Requirement = {id: number, name: string, valueType: 'YES_NO' | 'TEXT' | 'DATE' | 'NUMBER', required: boolean}
type Member = {participantId?: number, surname: string, givenName: string, patronymic: string}
type Answer = {requirementId: number, value: string | number | boolean}
type Crew = {id: number, categoryId: number, crewName: string, regionOrClub: string, representative: string, startNumber: number, version: number, roster: Member[], requirementAnswers: Answer[], admissionDecision: boolean | null, eligible: boolean}
type Summary = Omit<Crew, 'representative' | 'requirementAnswers'>
type Draft = {categoryId: number, crewName: string, regionOrClub: string, representative: string, startNumber: string, roster: Member[], answers: Record<number, string>, editing?: Crew}
const member = (): Member => ({surname: '', givenName: '', patronymic: ''})
const empty = (category: Category): Draft => ({categoryId: category.id, crewName: '', regionOrClub: '', representative: '', startNumber: '', roster: Array.from({length: category.crewSize}, member), answers: {}})

export default function Crews({readOnly = false}: {readOnly?: boolean}) {
  const {t} = useTranslation()
  const [competitions, setCompetitions] = useState<Competition[]>([]), [competition, setCompetition] = useState<Competition | null>(null)
  const [categories, setCategories] = useState<Category[]>([]), [requirements, setRequirements] = useState<Requirement[]>([]), [crews, setCrews] = useState<Summary[]>([])
  const [category, setCategory] = useState(''), [search, setSearch] = useState(''), [admission, setAdmission] = useState('all'), [draft, setDraft] = useState<Draft | null>(null)
  const selection = useRef(0), editSelection = useRef(0)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [revision, setRevision] = useState(0)
  const base = competition ? `/api/competitions/${competition.id}/entries` : ''
  const fail = (e: unknown) => setError(apiErrorKey(e))
  useEffect(() => {
    let active = true
    const load = async () => {
      const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
      const pages = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
      if (active) setCompetitions([...first.content, ...pages.flatMap(page => page.content)])
    }
    void load().catch(e => { if (active) fail(e) })
    return () => { active = false }
  }, [])
  useEffect(() => {
    if (!competition) return
    let active = true
    Promise.all([apiRequest<Category[]>(`/api/competitions/${competition.id}/categories`), readOnly ? Promise.resolve([] as Requirement[]) : apiRequest<Requirement[]>(`${base}/requirements`), apiRequest<Summary[]>(readOnly ? base : `${base}/private`)])
      .then(([cats, reqs, list]) => {
        if (active) { setCategories(cats); setRequirements(reqs); setCrews(readOnly ? list.map(c => ({...c, roster: [], admissionDecision: null, version: 0, eligible: false})) : list) }
      }).catch(e => { if (active) fail(e) })
    return () => { active = false }
  }, [base, competition, revision, readOnly])
  const changeCompetition = async (id: string) => {
    editSelection.current++; const generation = ++selection.current
    setCompetition(null); setDraft(null); setCategories([]); setCrews([]); setCategory(''); setAdmission('all'); setError('')
    if (!id) { setCompetition(null); return }
    try { const result = await apiRequest<Competition>(`/api/competitions/${id}`); if (selection.current === generation) setCompetition(result) } catch (e) { if (selection.current === generation) fail(e) }
  }
  const edit = (item: Crew) => setDraft({categoryId: item.categoryId, crewName: item.crewName, regionOrClub: item.regionOrClub, representative: item.representative, startNumber: String(item.startNumber), roster: item.roster.map(m => ({...m, patronymic: m.patronymic ?? ''})), answers: Object.fromEntries(item.requirementAnswers.map(a => [a.requirementId, String(a.value)])), editing: item})
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!draft || busy || hasConflict) return
    setBusy(true); setError('')
    try {
      const answers = requirements.filter(r => draft.answers[r.id] !== undefined && draft.answers[r.id] !== '').map(r => ({requirementId: r.id, value: r.valueType === 'YES_NO' ? draft.answers[r.id] === 'true' : r.valueType === 'NUMBER' ? draft.answers[r.id] : draft.answers[r.id]}))
      const exactAnswers = answers.map(a => requirements.find(r => r.id === a.requirementId)?.valueType === 'NUMBER' ? {requirementId: a.requirementId, decimalValue: a.value} : a)
      const roster = draft.roster.map(m => m.participantId ? {participantId: m.participantId} : {surname: m.surname, givenName: m.givenName, patronymic: m.patronymic || null})
      await csrfRequest(draft.editing ? `${base}/${draft.editing.id}` : base, draft.editing ? 'PUT' : 'POST', {...(!draft.editing ? {categoryId: draft.categoryId} : {}), crewName: draft.crewName, regionOrClub: draft.regionOrClub, representative: draft.representative, startNumber: Number(draft.startNumber), roster, requirementAnswers: exactAnswers, ...(draft.editing ? {version: draft.editing.version} : {})})
      setDraft(null); setRevision(v => v + 1)
    } catch (e) { fail(e) } finally { setBusy(false) }
  }
  const decide = async (item: Summary, admitted: boolean) => {
    setBusy(true); setError('')
    try { await csrfRequest(`${base}/${item.id}/admission`, 'PUT', {admitted, version: item.version}); setRevision(v => v + 1) } catch (e) { fail(e) } finally { setBusy(false) }
  }
  const conflict = (m: Member) => draft && m.participantId ? crews.find(c => c.categoryId === draft.categoryId && c.id !== draft.editing?.id && c.roster.some(person => person.participantId === m.participantId)) : undefined
  const hasConflict = draft?.roster.some(m => !!conflict(m)) ?? false
  const people = Array.from(new Map(crews.flatMap(c => c.roster).filter(m => m.participantId).map(m => [m.participantId, m])).values())
  const visible = crews.filter(c => (!category || String(c.categoryId) === category) && `${c.crewName} ${c.regionOrClub} ${c.startNumber} ${c.roster.map(m => `${m.surname} ${m.givenName} ${m.patronymic ?? ''}`).join(' ')}`.toLocaleLowerCase().includes(search.toLocaleLowerCase()) && (admission === 'all' || String(c.admissionDecision) === admission))
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    <TextField select disabled={busy} label={t('crew.competition')} value={competition?.id ?? ''} onChange={e => void changeCompetition(e.target.value)}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={c.id}>{c.title}</MenuItem>)}</TextField>
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    {competition && <><TextField select label={t('crew.category')} value={category} onChange={e => setCategory(e.target.value)}><MenuItem value="">{t('crew.all')}</MenuItem>{categories.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>
      <TextField label={t('crew.search')} value={search} onChange={e => setSearch(e.target.value)}/>
      {!readOnly && competition.admissionConfirmationRequired && <TextField select label={t('crew.admission')} value={admission} onChange={e => setAdmission(e.target.value)}><MenuItem value="all">{t('crew.all')}</MenuItem><MenuItem value="null">{t('crew.pending')}</MenuItem><MenuItem value="true">{t('crew.admitted')}</MenuItem><MenuItem value="false">{t('crew.rejected')}</MenuItem></TextField>}
      {!readOnly && <Button disabled={busy || !category} onClick={() => { editSelection.current++; const c = categories.find(c => String(c.id) === category); if (c) setDraft(empty(c)) }}>{t('crew.create')}</Button>}
      {visible.map(c => { const d = c; return <Box key={c.id}><Typography>{c.startNumber} — {c.crewName} — {c.regionOrClub} — {categories.find(category => category.id === c.categoryId)?.displayName} {readOnly ? null : ` — ${c.roster.length}`}</Typography>{!readOnly && d && <><Button disabled={busy} onClick={() => { const generation = selection.current, editGeneration = ++editSelection.current; void apiRequest<Crew>(`${base}/${d.id}`).then(item => { if (selection.current === generation && editSelection.current === editGeneration) edit(item) }).catch(e => { if (selection.current === generation && editSelection.current === editGeneration) fail(e) }) }}>{t('crew.edit')}</Button>{competition.admissionConfirmationRequired && <><Typography component="span">{t(d.admissionDecision === null ? 'crew.pending' : d.admissionDecision ? 'crew.admitted' : 'crew.rejected')}</Typography><Button disabled={busy} onClick={() => void decide(d, true)}>{t('crew.admit')}</Button><Button disabled={busy} onClick={() => void decide(d, false)}>{t('crew.reject')}</Button></>}</>}</Box> })}
      {draft && <Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2}}>
        {(['crewName', 'regionOrClub', 'representative', 'startNumber'] as const).map(key => <TextField key={key} required label={t(`crew.${key}`)} type={key === 'startNumber' ? 'number' : 'text'} value={draft[key]} onChange={e => setDraft({...draft, [key]: e.target.value})}/>)}
        {draft.roster.map((m, i) => <Box key={i} component="fieldset"><legend>{t('crew.member', {number: i + 1})}</legend><TextField select label={t('crew.existingParticipant')} value={m.participantId ?? ''} onChange={e => { const selected = people.find(person => String(person.participantId) === String(e.target.value)); const roster = [...draft.roster]; roster[i] = selected ? {...selected} : member(); setDraft({...draft, roster}) }}><MenuItem value="">{t('crew.newParticipant')}</MenuItem>{people.map(person => <MenuItem key={person.participantId} value={person.participantId}>{person.surname} {person.givenName} {person.patronymic}</MenuItem>)}</TextField>{conflict(m) && <Typography role="status">{t('crew.participantConflict', {name: conflict(m)?.crewName})}</Typography>}{(['surname', 'givenName', 'patronymic'] as const).map(key => <TextField key={key} required={key !== 'patronymic'} label={t(`crew.${key}`)} value={m[key]} onChange={e => {const roster = [...draft.roster]; roster[i] = {...m, participantId: undefined, [key]: e.target.value}; setDraft({...draft, roster})}}/>)}</Box>)}
        {requirements.map(r => <TextField key={r.id} select={r.valueType === 'YES_NO'} label={r.name} required={r.required} type={r.valueType === 'DATE' ? 'date' : r.valueType === 'NUMBER' ? 'number' : 'text'} slotProps={r.valueType === 'DATE' ? {inputLabel: {shrink: true}} : r.valueType === 'NUMBER' ? {htmlInput: {step: 'any'}} : undefined} value={draft.answers[r.id] ?? ''} onChange={e => setDraft({...draft, answers: {...draft.answers, [r.id]: e.target.value}})}>{r.valueType === 'YES_NO' && [<MenuItem key="empty" value="">—</MenuItem>, <MenuItem key="yes" value="true">{t('crew.yes')}</MenuItem>, <MenuItem key="no" value="false">{t('crew.no')}</MenuItem>]}</TextField>)}
        <Box><Button type="submit" disabled={busy || hasConflict}>{t('crew.save')}</Button><Button disabled={busy} onClick={() => { editSelection.current++; setDraft(null) }}>{t('crew.cancel')}</Button></Box>
      </Box>}</>}
  </Box>
}
