import {useEffect, useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {useTranslation} from 'react-i18next'
import {Box, Button, MenuItem, TextField, Typography} from '@mui/material'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Policy = 'HIDDEN' | 'OPTIONAL' | 'REQUIRED'
type Field = 'representative' | 'phone' | 'whatsapp' | 'telegram' | 'email'
type Competition = {id: number, title: string, status?: string, admissionConfirmationRequired: boolean, representativeFields?: Record<Field, Policy>}
type SearchResult = {id: number, competitionId: number, categoryId: number, crewName: string, regionOrClub: string, startDate: string, competitionTitle: string, categoryName: string, startNumber: number, rosterVariant?: number | null, roster?: Member[]}
type Category = {id: number, displayName: string, crewSize: number, version: number, seatLabels?: string[]}
type Requirement = {id: number, name: string, valueType: 'YES_NO' | 'TEXT' | 'DATE' | 'NUMBER', required: boolean}
type Member = {participantId?: number, surname: string, givenName: string, patronymic: string}
type Answer = {requirementId: number, value: string | number | boolean}
type Crew = {id: number, categoryId: number, crewName: string, regionOrClub: string, representative: string, representativeContacts?: Record<string, string>, representativePosition?: number | null, startNumber: number, version: number, roster: Member[], requirementAnswers: Answer[], admissionDecision: boolean | null, eligible: boolean}
type Summary = Omit<Crew, 'representative' | 'requirementAnswers'>
type Draft = {categoryId: number, crewName: string, regionOrClub: string, representative: string, representativeIndex?: number, contacts: Record<string, string>, startNumber: string, roster: Member[], answers: Record<number, string>, editing?: Crew}
const member = (): Member => ({surname: '', givenName: '', patronymic: ''})
const nextNumber = (category: Category, crews: Summary[]) => {
  const used = new Set(crews.filter(c => c.categoryId === category.id).map(c => c.startNumber))
  let number = 1
  while (used.has(number)) number++
  return String(number)
}
const empty = (category: Category, crews: Summary[]): Draft => ({categoryId: category.id, crewName: '', regionOrClub: '', representative: '', contacts: {}, startNumber: nextNumber(category, crews), roster: Array.from({length: category.crewSize}, member), answers: {}})

export default function Crews({readOnly = false, canEdit = !readOnly, fixedCompetition, onReadinessChange}: {readOnly?: boolean, canEdit?: boolean, fixedCompetition?: string, onReadinessChange?: () => void}) {
  const {t, i18n} = useTranslation()

  const [competitions, setCompetitions] = useState<Competition[]>([]), [competition, setCompetition] = useState<Competition | null>(null)
  const editable = !readOnly && canEdit && competition?.status !== 'COMPLETED'
  const [categories, setCategories] = useState<Category[]>([]), [requirements, setRequirements] = useState<Requirement[]>([]), [crews, setCrews] = useState<Summary[]>([])
  const [loadedCompetitionId, setLoadedCompetitionId] = useState<number | null>(null)
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])
  const searchGeneration = useRef(0)
  const [category, setCategory] = useState(() => fixedCompetition ? new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('category') ?? '' : ''), [search, setSearch] = useState(''), [admission, setAdmission] = useState('all'), [draft, setDraft] = useState<Draft | null>(null)
  useEffect(() => {
    if (!fixedCompetition) return
    const [route, query = ''] = window.location.hash.slice(1).split('?')
    const next = new URLSearchParams(query)
    if (category) next.set('category', category); else next.delete('category')
    window.history.replaceState(window.history.state, '', `#${route}${next.size ? '?' + next : ''}`)
    window.dispatchEvent(new Event('workspace-filter-change'))
  }, [fixedCompetition, category])
  const selection = useRef(0), editSelection = useRef(0)
  const initialDraft = useRef<Draft | null>(null)
  const startDraft = (value: Draft) => { initialDraft.current = value; setDraft(value) }
  const canDiscard = () => !draft || JSON.stringify(draft) === JSON.stringify(initialDraft.current) || window.confirm(t('resultGrid.discard'))
  const [pendingCrew, setPendingCrew] = useState<number | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [revision, setRevision] = useState(0)
  const base = competition ? `/api/competitions/${competition.id}/entries` : ''
  const fail = (e: unknown) => setError(apiErrorKey(e))
  useEffect(() => {
    let active = true
    const load = async () => {
      if (fixedCompetition) {const item = await apiRequest<Competition>(`/api/competitions/${fixedCompetition}`); if (active) {setCompetitions([item]); setCompetition(item)}; return}
      const first = await apiRequest<{content: Competition[], totalPages: number}>('/api/competitions?size=100')
      const pages = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, i) => apiRequest<{content: Competition[]}>(`/api/competitions?size=100&page=${i + 1}`)))
      if (active) setCompetitions([...first.content, ...pages.flatMap(page => page.content)])
    }
    void load().catch(e => { if (active) fail(e) })
    return () => { active = false }
  }, [fixedCompetition])
  useEffect(() => {
    if (!competition) return
    let active = true
    Promise.all([apiRequest<Category[]>(`/api/competitions/${competition.id}/categories`), readOnly ? Promise.resolve([] as Requirement[]) : apiRequest<Requirement[]>(`${base}/requirements`), apiRequest<Summary[]>(readOnly ? base : `${base}/private`)])
      .then(([cats, reqs, list]) => {
        if (active) { setCategories(cats); setCategory(current => current && !cats.some(c => String(c.id) === current) ? '' : current); setRequirements(reqs); setLoadedCompetitionId(competition.id); setCrews(readOnly ? list.map(c => ({...c, roster: [], admissionDecision: null, version: 0, eligible: false})) : list) }
      }).catch(e => { if (active) fail(e) })
    return () => { active = false }
  }, [base, competition, revision, readOnly])
  useEffect(() => {
    if (!search.trim()) return
    let active = true
    const generation = searchGeneration.current
    const timer = setTimeout(() => { void apiRequest<SearchResult[]>(`/api/crew-search${readOnly ? '/public' : ''}?name=${encodeURIComponent(search.trim())}`).then(results => {if (active && searchGeneration.current === generation) setSearchResults(results)}).catch(e => {if (active && searchGeneration.current === generation) fail(e)}) }, 250)
    return () => {active = false; clearTimeout(timer)}
  }, [search, readOnly])
  const changeCompetition = async (id: string) => {
    if (!canDiscard()) return
    editSelection.current++; const generation = ++selection.current
    setCompetition(null); setLoadedCompetitionId(null); setDraft(null); setPendingCrew(null); setCategories([]); setCrews([]); setCategory(''); setAdmission('all'); setError('')
    if (!id) { setCompetition(null); return }
    try { const result = await apiRequest<Competition>(`/api/competitions/${id}`); if (selection.current === generation) setCompetition(result) } catch (e) { if (selection.current === generation) fail(e) }
  }
  const edit = (item: Crew) => startDraft({categoryId: item.categoryId, crewName: item.crewName, regionOrClub: item.regionOrClub, representative: item.representative, representativeIndex: item.representativePosition == null ? undefined : item.representativePosition - 1, contacts: item.representativeContacts ?? {}, startNumber: String(item.startNumber), roster: item.roster.map(m => ({...m, patronymic: m.patronymic ?? ''})), answers: Object.fromEntries(item.requirementAnswers.map(a => [a.requirementId, String(a.value)])), editing: item})
  const openCrew = (id: number) => {
    if (!canDiscard()) return
    const generation = selection.current, editGeneration = ++editSelection.current
    setDraft(null); setPendingCrew(id); setError('')
    void apiRequest<Crew>(`${base}/${id}`).then(item => {
      if (selection.current === generation && editSelection.current === editGeneration) { setPendingCrew(null); edit(item) }
    }).catch(e => { if (selection.current === generation && editSelection.current === editGeneration) { setPendingCrew(null); fail(e) } })
  }
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!draft || busy || hasConflict) return
    setBusy(true); setError('')
    try {
      const answers = requirements.filter(r => draft.answers[r.id] !== undefined && draft.answers[r.id] !== '').map(r => ({requirementId: r.id, value: r.valueType === 'YES_NO' ? draft.answers[r.id] === 'true' : r.valueType === 'NUMBER' ? draft.answers[r.id] : draft.answers[r.id]}))
      const exactAnswers = answers.map(a => requirements.find(r => r.id === a.requirementId)?.valueType === 'NUMBER' ? {requirementId: a.requirementId, decimalValue: a.value} : a)
      const single = categories.find(c => c.id === draft.categoryId)?.crewSize === 1
      const fullName = (m: Member) => [m.surname, m.givenName, m.patronymic].filter(part => part.trim()).join(' ')
      const roster = draft.roster.map(m => m.participantId ? {participantId: m.participantId} : {surname: m.surname, givenName: m.givenName, patronymic: m.patronymic || null})
      const saved = await csrfRequest<Crew>(draft.editing ? `${base}/${draft.editing.id}` : base, draft.editing ? 'PUT' : 'POST', {...(!draft.editing ? {categoryId: draft.categoryId} : {}), crewName: single ? fullName(draft.roster[0]) : draft.crewName, regionOrClub: draft.regionOrClub, representative: single ? (draft.editing?.representativePosition === 1 ? fullName(draft.roster[0]) : draft.editing?.representative ?? '') : draft.representativeIndex === undefined ? draft.representative : [draft.roster[draft.representativeIndex].surname, draft.roster[draft.representativeIndex].givenName, draft.roster[draft.representativeIndex].patronymic].filter(part => part.trim()).join(' '), representativeContacts: draft.contacts, representativePosition: single ? (draft.editing?.representativePosition ?? null) : draft.representativeIndex === undefined ? null : draft.representativeIndex + 1, startNumber: Number(draft.startNumber), roster, requirementAnswers: exactAnswers, ...(draft.editing ? {version: draft.editing.version} : {})})
      if (saved?.id != null) setCrews(items => [...items.filter(item => item.id !== saved.id), saved])
      setDraft(null); setRevision(v => v + 1); onReadinessChange?.()
    } catch (e) { fail(e) } finally { setBusy(false) }
  }
  const decide = async (item: Summary, admitted: boolean) => {
    setBusy(true); setError('')
    try { await csrfRequest(`${base}/${item.id}/admission`, 'PUT', {admitted, version: item.version}); setRevision(v => v + 1); onReadinessChange?.() } catch (e) { fail(e) } finally { setBusy(false) }
  }
  const conflict = (m: Member) => draft && m.participantId ? crews.find(c => c.categoryId === draft.categoryId && c.id !== draft.editing?.id && c.roster.some(person => person.participantId === m.participantId)) : undefined
  const hasConflict = draft?.roster.some(m => !!conflict(m)) ?? false
  const people = Array.from(new Map([...crews.flatMap(c => c.roster), ...(draft?.roster ?? [])].filter(m => m.participantId).map(m => [m.participantId, m])).values())
  const visible = crews.filter(c => (!category || String(c.categoryId) === category) && (admission === 'all' || String(c.admissionDecision) === admission))
  const policies = competition?.representativeFields ?? {representative: 'REQUIRED', phone: 'OPTIONAL', whatsapp: 'OPTIONAL', telegram: 'OPTIONAL', email: 'OPTIONAL'}
  const searchTarget = (item: SearchResult) => {
    const compatible = categories.filter(c => c.crewSize === item.roster?.length)
    return category ? categories.find(c => String(c.id) === category) : compatible.length === 1 ? compatible[0] : undefined
  }
  const selectSearch = async (item: SearchResult) => {
    const target = searchTarget(item)
    if (!target || !item.roster || target.crewSize !== item.roster.length || !canDiscard()) return
    setCategory(String(target.id))
    const generation = selection.current, editGeneration = ++editSelection.current
    setPendingCrew(null)
    try {
      const result = await apiRequest<Crew>(`/api/competitions/${item.competitionId}/entries/${item.id}`)
      if (generation !== selection.current || editGeneration !== editSelection.current) return
      if (item.competitionId === competition?.id && item.categoryId === target.id) { edit(result); return }
      const contacts = Object.fromEntries(Object.entries(result.representativeContacts ?? {}).filter(([key]) => policies[key as Field] !== 'HIDDEN'))
      startDraft({...empty(target, crews), crewName: result.crewName, regionOrClub: result.regionOrClub ?? '',
        representative: policies.representative === 'HIDDEN' ? '' : result.representative,
        representativeIndex: policies.representative === 'HIDDEN' || result.representativePosition == null ? undefined : result.representativePosition - 1,
        contacts, roster: result.roster.map(person => ({...person, patronymic: person.patronymic ?? ''}))})
    } catch (e) { if (generation === selection.current && editGeneration === editSelection.current) fail(e) }

  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    {!fixedCompetition && <TextField select disabled={busy || !!fixedCompetition} label={t('crew.competition')} value={competition?.id ?? ''} onChange={e => void changeCompetition(e.target.value)}><MenuItem value="">—</MenuItem>{competitions.map(c => <MenuItem key={c.id} value={c.id}>{c.title}</MenuItem>)}</TextField>}
    {error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
    {competition && <TextField select disabled={busy} label={t('crew.category')} value={category} onChange={e => {if (!canDiscard()) return; editSelection.current++; setPendingCrew(null); setDraft(null); setCategory(e.target.value)}}><MenuItem value="">{t('crew.all')}</MenuItem>{categories.map(c => <MenuItem key={c.id} value={String(c.id)}>{c.displayName}</MenuItem>)}</TextField>}
    {competition && editable && <TextField select label={t('crew.selectCrew')} disabled={busy || !category} value={pendingCrew !== null && crews.some(c => c.id === pendingCrew && String(c.categoryId) === category) ? String(pendingCrew) : draft?.editing && String(draft.categoryId) === category ? String(draft.editing.id) : ''} onChange={e => { if (e.target.value) openCrew(Number(e.target.value)); else { if (!canDiscard()) return; editSelection.current++; setPendingCrew(null); setDraft(null) } }}><MenuItem value="">—</MenuItem>{crews.filter(c => String(c.categoryId) === category).map(c => <MenuItem key={c.id} value={String(c.id)}>{c.startNumber}. {c.crewName}{c.regionOrClub?.trim() && ` (${c.regionOrClub.trim()})`}</MenuItem>)}</TextField>}
    {!readOnly && competition?.status === 'COMPLETED' && <Typography>{t('crew.completedTarget')}</Typography>}
    {competition && <>

      {!readOnly && competition.admissionConfirmationRequired && <TextField select label={t('crew.admission')} value={admission} onChange={e => setAdmission(e.target.value)}><MenuItem value="all">{t('crew.all')}</MenuItem><MenuItem value="null">{t('crew.pending')}</MenuItem><MenuItem value="true">{t('crew.admitted')}</MenuItem><MenuItem value="false">{t('crew.rejected')}</MenuItem></TextField>}
      {editable && <Button disabled={busy || !category || loadedCompetitionId !== competition?.id} onClick={() => { if (!canDiscard()) return; editSelection.current++; setPendingCrew(null); const c = categories.find(c => String(c.id) === category); if (c) startDraft(empty(c, crews)) }}>{t('crew.create')}</Button>}
      {editable && !category && <Typography>{t('crew.chooseCategoryFirst')}</Typography>}
      {editable && draft && <Box component="form" onSubmit={save} sx={{display: 'grid', gap: 2}}>
        {(draft.roster.length === 1 ? ['regionOrClub', 'startNumber'] as const : ['crewName', 'regionOrClub', 'startNumber'] as const).map(key => <TextField key={key} required={key !== 'regionOrClub'} label={t(`crew.${key}`)} type={key === 'startNumber' ? 'number' : 'text'} value={draft[key]} onChange={e => setDraft({...draft, [key]: e.target.value})}/>)}
        {draft.roster.map((m, i) => <Box key={i} component="fieldset"><legend>{categories.find(category => category.id === draft.categoryId)?.seatLabels?.[i]?.trim() || t('crew.member', {number: i + 1})}</legend><TextField select label={t('crew.existingParticipant')} value={m.participantId ?? ''} onChange={e => { const selected = people.find(person => String(person.participantId) === String(e.target.value)); const roster = [...draft.roster]; roster[i] = selected ? {...selected, patronymic: selected.patronymic ?? ''} : member(); setDraft({...draft, roster}) }}><MenuItem value="">{t('crew.newParticipant')}</MenuItem>{people.map(person => <MenuItem key={person.participantId} value={person.participantId}>{person.surname} {person.givenName} {person.patronymic}</MenuItem>)}</TextField>{conflict(m) && <Typography role="status">{t('crew.participantConflict', {name: conflict(m)?.crewName})}</Typography>}{(['surname', 'givenName', 'patronymic'] as const).map(key => <TextField key={key} required={key !== 'patronymic'} label={t(`crew.${key}`)} value={m[key]} onChange={e => {const roster = [...draft.roster]; roster[i] = {...m, participantId: undefined, [key]: e.target.value}; setDraft({...draft, roster})}}/>)}</Box>)}
        {requirements.map(r => <TextField key={r.id} select={r.valueType === 'YES_NO'} label={r.name} required={r.required} type={r.valueType === 'DATE' ? 'date' : r.valueType === 'NUMBER' ? 'number' : 'text'} slotProps={r.valueType === 'DATE' ? {inputLabel: {shrink: true}} : r.valueType === 'NUMBER' ? {htmlInput: {step: 'any'}} : undefined} value={draft.answers[r.id] ?? ''} onChange={e => setDraft({...draft, answers: {...draft.answers, [r.id]: e.target.value}})}>{r.valueType === 'YES_NO' && [<MenuItem key="empty" value="">—</MenuItem>, <MenuItem key="yes" value="true">{t('crew.yes')}</MenuItem>, <MenuItem key="no" value="false">{t('crew.no')}</MenuItem>]}</TextField>)}
        {draft.roster.length !== 1 && policies.representative !== 'HIDDEN' && <><TextField select label={t('crew.representativeChoice')} value={draft.representativeIndex ?? ''} onChange={e => setDraft({...draft, representativeIndex: e.target.value === '' ? undefined : Number(e.target.value)})}><MenuItem value="">{t('crew.newRepresentative')}</MenuItem>{draft.roster.map((m, i) => <MenuItem key={i} value={i}>{i + 1}. {m.surname} {m.givenName} {m.patronymic}</MenuItem>)}</TextField>{draft.representativeIndex === undefined && <TextField required={policies.representative === 'REQUIRED'} label={t('crew.representative')} value={draft.representative} onChange={e => setDraft({...draft, representative: e.target.value})}/>}</>}
        {draft.roster.length !== 1 && (['phone', 'whatsapp', 'telegram', 'email'] as const).filter(key => policies[key] !== 'HIDDEN').map(key => <TextField key={key} required={policies[key] === 'REQUIRED'} label={t(`crew.${key}`)} value={draft.contacts[key] ?? ''} onChange={e => setDraft({...draft, contacts: {...draft.contacts, [key]: e.target.value}})}/>)}
        <Box sx={{position: 'sticky', bottom: 0, backgroundColor: 'background.paper', py: 1, zIndex: 4}}><Button type="submit" disabled={busy || hasConflict}>{t('crew.save')}</Button><Button disabled={busy} onClick={() => { if (!canDiscard()) return; editSelection.current++; setPendingCrew(null); setDraft(null) }}>{t('crew.cancel')}</Button></Box>
      </Box>}
      {visible.map(c => <Box key={c.id}>
        <Typography>{[String(c.startNumber), c.crewName, c.regionOrClub?.trim(), categories.find(category => category.id === c.categoryId)?.displayName].filter(Boolean).join(' — ')}</Typography>
        {!readOnly && <>
          <Typography>{c.roster.map(m => [m.surname, m.givenName, m.patronymic].filter(Boolean).join(' ')).join(', ')}</Typography>
          {competition.admissionConfirmationRequired && <Typography component="span">{t(c.admissionDecision === null ? 'crew.pending' : c.admissionDecision ? 'crew.admitted' : 'crew.rejected')}</Typography>}
        </>}
        {editable && <>
          <Button disabled={busy} onClick={() => openCrew(c.id)}>{t('crew.edit')}</Button>
          {competition.admissionConfirmationRequired && <><Button disabled={busy} onClick={() => void decide(c, true)}>{t('crew.admit')}</Button><Button disabled={busy} onClick={() => void decide(c, false)}>{t('crew.reject')}</Button></>}
        </>}
      </Box>)}

      </>}
    {<>
      <TextField label={t('crew.search')} helperText={t('crew.searchHistoryHint')} value={search} onChange={e => {searchGeneration.current++; setSearchResults([]); setSearch(e.target.value)}}/>
      {search.trim() && searchResults.map(item => {
        const target = searchTarget(item)
        const compatible = categories.filter(c => c.crewSize === item.roster?.length)
        return <Box key={item.id}>
          <Typography>{item.startNumber} — {item.crewName} — {item.competitionTitle} — {item.categoryName} — {formatDate(new Date(`${item.startDate}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC')} — {item.regionOrClub}{readOnly && item.rosterVariant != null && ` — ${t('crew.rosterVariant', {number: item.rosterVariant})}`}{!readOnly && item.roster && ` — ${item.roster.map(m => `${m.surname} ${m.givenName} ${m.patronymic ?? ''}`).join(', ')}`}</Typography>
          {editable && item.roster && <><Button disabled={busy || !competition || loadedCompetitionId !== competition.id || !target || target.crewSize !== item.roster.length} onClick={() => void selectSearch(item)}>{t(item.competitionId === competition?.id && item.categoryId === target?.id ? 'crew.openApplication' : 'crew.copyApplication')}</Button>
          {!competition ? <Typography>{t('crew.chooseTarget')}</Typography> : !target && compatible.length === 0 ? <Typography>{t('crew.sizeMismatch')}</Typography> : !target ? <Typography>{t('crew.chooseCompatible', {categories: compatible.map(c => c.displayName).join(', ')})}</Typography> : target.crewSize !== item.roster.length ? <Typography>{t('crew.sizeMismatch')}</Typography> : <Typography>{t('crew.targetCategory', {category: target.displayName})}</Typography>}</>}
        </Box>
      })}
    </>}
  </Box>
}
