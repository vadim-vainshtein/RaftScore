import {useEffect, useRef, useState} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography, Tabs, Tab, IconButton} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey, fieldErrorKey} from './api/errors'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'
import TimeZoneField from './TimeZoneField'

type FieldIssue = {step: number, field: string, label: string, message?: string}
type Field = 'representative' | 'phone' | 'whatsapp' | 'telegram' | 'email'
type Policy = 'HIDDEN' | 'OPTIONAL' | 'REQUIRED'
type Competition = {title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean, representativeFields: Record<Field, Policy>}
type Template = {seatLabels?: string[], id: number, classLabel: string, scoringGroupLabel: string, crewSize: number, displayName: string, archived: boolean}
type Category = {seatLabels?: string[], templateId?: number, classLabel: string, scoringGroupLabel: string, crewSize: number}
type Requirement = {name: string, valueType: string, required: boolean, displayOrder: number}
type Row = {place: number, points: number}
type Discipline = {name: string, type: string, displayOrder: number, categoryIndexes: number[], gateCount: number | null, attemptCount: number | null, qualificationDisciplineIndex: number | null, pointsTable: {name: string, tiePolicy: string, rows: Row[]} | null}
const types = ['SPRINT', 'PARALLEL_SPRINT', 'SLALOM', 'LONG_RACE']
const multipliers = [1, 2, 3, 4]
const freshDiscipline = (order: number): Discipline => ({name: '', type: 'SPRINT', displayOrder: order, categoryIndexes: [], gateCount: null, attemptCount: null, qualificationDisciplineIndex: null, pointsTable: null})

export default function CompetitionWizard({onCancel, onSaved, draftKey}: {draftKey?: string, onCancel: () => void, onSaved: (value: {id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}) => void}) {
  const {t, i18n} = useTranslation()
  const {t: categoryT} = useTranslation('categories')
  const disciplineName = (d: Discipline) => d.name.trim() || t(('discipline.types.' + d.type) as 'discipline.types.SPRINT')
  const displayDate = (value: string) => value ? formatDate(new Date(`${value}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC') : '—'
  const [stored] = useState(() => { try { return draftKey ? JSON.parse(localStorage.getItem(draftKey) ?? 'null') : null } catch { return null } })
  const [step, setStep] = useState<number>(stored?.step ?? 0), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [competition, setCompetition] = useState<Competition>(stored?.competition ?? {title: '', startDate: '', endDate: '', location: '', timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', description: '', admissionConfirmationRequired: false, representativeFields: {representative: 'REQUIRED', phone: 'OPTIONAL', whatsapp: 'OPTIONAL', telegram: 'OPTIONAL', email: 'OPTIONAL'}})
  const [activeDiscipline, setActiveDiscipline] = useState(0)
  const [expandedPoints, setExpandedPoints] = useState<Record<number, boolean>>({})
  const [templates, setTemplates] = useState<Template[]>([]), [categories, setCategories] = useState<Category[]>(stored?.categories ?? [])
  const [requirements, setRequirements] = useState<Requirement[]>(stored?.requirements ?? []), [disciplines, setDisciplines] = useState<Discipline[]>(stored?.disciplines ?? [])
  const [issues, setIssues] = useState<FieldIssue[]>([])
  const [focusField, setFocusField] = useState<{field: string} | null>(null)
  const handledFocus = useRef<typeof focusField>(null)
  useEffect(() => { if (focusField && focusField !== handledFocus.current) { document.getElementById(focusField.field)?.focus(); handledFocus.current = focusField } }, [step, focusField])
  const [explicitTiebreak, setTiebreak] = useState<number | null>(stored?.explicitTiebreak ?? null)
  const [presetSelected, setPresetSelected] = useState(stored?.presetSelected ?? false)
  const [tiebreakExplicit, setTiebreakExplicit] = useState(stored?.tiebreakExplicit ?? false)
  useEffect(() => {if (draftKey) localStorage.setItem(draftKey, JSON.stringify({step, competition, categories, requirements, disciplines, explicitTiebreak, presetSelected, tiebreakExplicit}))}, [draftKey, step, competition, categories, requirements, disciplines, explicitTiebreak, presetSelected, tiebreakExplicit])
  const slalom = disciplines.findIndex(d => d.type === 'SLALOM')
  const tiebreak = presetSelected && !tiebreakExplicit ? (slalom < 0 ? null : slalom) : explicitTiebreak
  useEffect(() => {let active = true; (async () => {try {let page = 0, total = 1; const all: Template[] = []; while (page < total) {const result = await apiRequest<{content: Template[], totalPages: number}>(`/api/category-templates?archived=false&page=${page}&size=100`); all.push(...result.content.filter(item => !item.archived)); total = result.totalPages; page++} if (active) setTemplates(all)} catch {if (active) setError('wizard.templatesError')}})(); return () => {active = false}}, [t])
  const updateDiscipline = (i: number, patch: Partial<Discipline>) => setDisciplines(items => items.map((d, index) => index === i ? {...d, ...patch} : d))
  const fieldIssues = (currentStep: number): FieldIssue[] => {
    const found: FieldIssue[] = []
    const add = (field: string, label: string, message = 'apiError.required') => found.push({step: currentStep, field, label, message})
    if (currentStep === 0) {
      if (!competition.title.trim()) add('competition.title', t('competition.title'))
      if (!competition.startDate) add('competition.startDate', t('competition.startDate'))
      if (competition.endDate && competition.endDate < competition.startDate) add('competition.endDate', t('competition.endDate'), 'wizard.endDateBeforeStart')
      if (!competition.location.trim()) add('competition.location', t('competition.location'))
      try { new Intl.DateTimeFormat('en', {timeZone: competition.timeZone}) } catch { add('competition.timeZone', t('competition.timeZone'), 'apiError.invalidField') }
    }
    if (currentStep === 1) categories.forEach((c, i) => {
      if (!c.classLabel.trim()) add(`categories[${i}].classLabel`, t('wizard.classLabel'))
      if (!c.scoringGroupLabel.trim()) add(`categories[${i}].scoringGroupLabel`, t('wizard.scoringGroupLabel'))
      if (!Number.isInteger(c.crewSize) || c.crewSize < 1) add(`categories[${i}].crewSize`, t('wizard.crewSize'), 'wizard.positiveInteger')
      c.seatLabels?.slice(0, c.crewSize).forEach((label, n) => { if (label.length > 200 || label.includes('\0')) add(`categories[${i}].seatLabels[${n}]`, categoryT('seat', {number: n + 1}), 'wizard.invalidSeatLabel') })
    })
    if (currentStep === 2) requirements.forEach((r, i) => { if (!r.name.trim()) add(`requirements[${i}].name`, t('wizard.requirementName')) })
    if (currentStep === 3) disciplines.forEach((d, i) => {
      if (d.type === 'SLALOM') {
        if (!Number.isInteger(d.gateCount) || (d.gateCount ?? 0) < 1) add(`disciplines[${i}].gateCount`, t('discipline.gateCount'), 'wizard.positiveInteger')
        if (!Number.isInteger(d.attemptCount) || (d.attemptCount ?? 0) < 1) add(`disciplines[${i}].attemptCount`, t('discipline.attemptCount'), 'wizard.positiveInteger')
      }
      if (d.qualificationDisciplineIndex !== null && (!disciplines[d.qualificationDisciplineIndex] || disciplines[d.qualificationDisciplineIndex].type === 'PARALLEL_SPRINT' || !d.categoryIndexes.every(x => disciplines[d.qualificationDisciplineIndex!].categoryIndexes.includes(x)))) add(`disciplines[${i}].qualificationDisciplineIndex`, t('discipline.qualification'), 'wizard.invalidQualification')
      d.pointsTable?.rows.forEach((r, n) => {
        if (!Number.isInteger(r.place) || r.place < 1 || d.pointsTable!.rows.filter(x => x.place === r.place).length > 1) add(`disciplines[${i}].pointsTable.rows[${n}].place`, t('wizard.place'), 'wizard.uniquePlace')
        if (!Number.isInteger(r.points) || r.points < 0) add(`disciplines[${i}].pointsTable.rows[${n}].points`, t('wizard.points'), 'wizard.nonnegativeInteger')
      })
    })
    return found
  }
  const next = () => {
    setError('')
    const found = fieldIssues(step)
    setIssues(found)
    if (!found.length) setStep(step + 1)
  }
  const serverIssues = (failure: unknown): FieldIssue[] => {
    if (!(failure instanceof ApiError) || !failure.body || typeof failure.body !== 'object') return []
    const fields = (failure.body as {fieldErrors?: unknown}).fieldErrors
    if (!Array.isArray(fields)) return []
    return fields.flatMap(item => {
      const field = item?.field
      if (typeof field !== 'string') return []
      const targetStep = field.startsWith('competition.') ? 0 : field.startsWith('categories[') ? 1 : field.startsWith('requirements[') ? 2 : field.startsWith('disciplines[') ? 3 : -1
      const name = field.split('.').at(-1) ?? ''
      const labels: Record<string, string> = {title: t('competition.title'), startDate: t('competition.startDate'), endDate: t('competition.endDate'), location: t('competition.location'), timeZone: t('competition.timeZone'), description: t('competition.description'), classLabel: t('wizard.classLabel'), scoringGroupLabel: t('wizard.scoringGroupLabel'), crewSize: t('wizard.crewSize'), name: targetStep === 2 ? t('wizard.requirementName') : t('discipline.name'), gateCount: t('discipline.gateCount'), attemptCount: t('discipline.attemptCount'), qualificationDisciplineIndex: t('discipline.qualification'), displayOrder: t('discipline.order'), place: t('wizard.place'), points: t('wizard.points')}
      return targetStep < 0 || !document.getElementById(field) && !/^(competition\.(title|startDate|endDate|location|timeZone|description)|categories\[\d+\]\.(classLabel|scoringGroupLabel|crewSize)|requirements\[\d+\]\.name|disciplines\[\d+\]\.(name|gateCount|attemptCount|qualificationDisciplineIndex|displayOrder|pointsTable\.rows\[\d+\]\.(place|points)))$/.test(field) ? [] : [{step: targetStep, field, label: labels[name] ?? t('apiError.invalidField'), message: fieldErrorKey(item?.code)}]
    })
  }
  const applyPreset = (index: number, discipline: Discipline) => {
    const rows = Array.from({length: 20}, (_, n) => ({place: n + 1, points: (105 - 5 * (n + 1)) * multipliers[types.indexOf(discipline.type)]}))
    updateDiscipline(index, {pointsTable: {name: t('wizard.preset'), tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows}})
    setPresetSelected(true)
  }
  const save = async () => {setBusy(true); setError(''); try {const result = await csrfRequest<{id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}>('/api/competitions/setup', 'POST', {competition: {...competition, endDate: competition.endDate || null}, categories: categories.map(c => ({...c, seatLabels: c.seatLabels?.slice(0, c.crewSize)})), requirements, disciplines, tiebreakDisciplineIndex: tiebreak}); if (draftKey) localStorage.removeItem(draftKey); onSaved(result)} catch (failure) {const found = serverIssues(failure); setIssues(found); if (!found.length) setError(apiErrorKey(failure))} finally {setBusy(false)}}
  return <Box sx={{display: 'grid', gap: 2, maxWidth: 850}}>
    <Typography variant="h5">{t(("wizard.step" + step) as "wizard.step0")}
      </Typography>
      <Typography>{step + 1} / 5</Typography>{error && <Typography role="alert" color="error">{t(error as 'apiError.network')}</Typography>}
      {issues.length > 0 && <Box role="alert" sx={{color: 'error.main', border: 1, p: 1}}>{[...new Set(issues.map(issue => issue.step))].map(group => <Box key={group}><Typography>{t(('wizard.step' + group) as 'wizard.step0')}</Typography>{issues.filter(issue => issue.step === group).map((issue, index) => <Box key={index}><Button color="error" onClick={() => {setStep(issue.step); const match = issue.field.match(/^disciplines\[(\d+)\]/); if (match) setActiveDiscipline(Number(match[1])); setFocusField({field: issue.field})}}>{issue.label}</Button>{issue.message && <Typography component="span">{t(issue.message as 'apiError.required')}</Typography>}</Box>)}</Box>)}</Box>}
    {step === 0 && <>
      <TextField id="competition.title" label={t('competition.title')} value={competition.title} onChange={e => setCompetition({...competition, title: e.target.value})}/>
      <TextField id="competition.startDate" type="date" label={t('competition.startDate')} slotProps={{inputLabel: {shrink: true}}} value={competition.startDate} onChange={e => setCompetition({...competition, startDate: e.target.value})}/>
      <TextField id="competition.endDate" type="date" label={t('competition.endDate')} slotProps={{inputLabel: {shrink: true}}} value={competition.endDate} onChange={e => setCompetition({...competition, endDate: e.target.value})}/>
      <TextField id="competition.location" label={t('competition.location')} value={competition.location} onChange={e => setCompetition({...competition, location: e.target.value})}/>
      <TimeZoneField id="competition.timeZone" value={competition.timeZone} date={competition.startDate} onChange={timeZone => setCompetition({...competition, timeZone})}/>
      <TextField id="competition.description" label={t('competition.description')} multiline value={competition.description ?? ''} onChange={e => setCompetition({...competition, description: e.target.value})}/>
      </>}
    {step === 1 && <>
      <TextField select label={t('wizard.template')} value="" onChange={e => {const item = templates.find(x => x.id === Number(e.target.value)); if (item) setCategories([...categories, {templateId: item.id, classLabel: item.classLabel, scoringGroupLabel: item.scoringGroupLabel, crewSize: item.crewSize, seatLabels: item.seatLabels ?? []}])}}>{templates.map(item => <MenuItem key={item.id} value={item.id}>{item.displayName}</MenuItem>)}
      </TextField>
      <Button onClick={() => setCategories([...categories, {classLabel: '', scoringGroupLabel: '', crewSize: 1, seatLabels: []}])}>{t('wizard.addCategory')}
      </Button>{categories.map((c, i) => <Box key={i} sx={{display: 'flex', gap: 1, flexWrap: 'wrap'}}>{(['classLabel', 'scoringGroupLabel', 'crewSize'] as const).map(key => <TextField key={key} id={`categories[${i}].${key}`} label={t(`wizard.${key}`)} type={key === 'crewSize' ? 'number' : 'text'} value={c[key]} onChange={e => setCategories(categories.map((item, index) => index === i ? {...item, [key]: key === 'crewSize' ? Number(e.target.value) : e.target.value} : item))}/>)}
      <Button onClick={() => {setCategories(categories.filter((_, index) => index !== i)); setDisciplines(disciplines.map(d => ({...d, categoryIndexes: d.categoryIndexes.filter(x => x !== i).map(x => x > i ? x - 1 : x)})))}}>{t('wizard.remove')}
      </Button>
      <Typography>{c.classLabel} / {c.scoringGroupLabel}</Typography>
      {Array.from({length: Math.min(Math.max(0, c.crewSize), Math.max(8, c.seatLabels?.length ?? 0))}, (_, n) => <TextField key={n} id={`categories[${i}].seatLabels[${n}]`} label={categoryT('seat', {number: n + 1})} value={c.seatLabels?.[n] ?? ''} onChange={e => setCategories(categories.map((item, index) => { if (index !== i) return item; const labels = Array.from({length: Math.max(n + 1, item.seatLabels?.length ?? 0)}, (_, k) => item.seatLabels?.[k] ?? ''); labels[n] = e.target.value; return {...item, seatLabels: labels} }))}/>)}
      <Button disabled={Math.max(8, c.seatLabels?.length ?? 0) >= c.crewSize} onClick={() => setCategories(categories.map((item, index) => index !== i ? item : {...item, seatLabels: Array.from({length: Math.max(8, item.seatLabels?.length ?? 0) + 1}, (_, n) => item.seatLabels?.[n] ?? '')}))}>{categoryT('addSeat')}</Button>
      </Box>)}
      </>}
    {step === 2 && <><Typography>{t('crew.representativeSettings')}</Typography>{(['representative', 'phone', 'whatsapp', 'telegram', 'email'] as const).map(field => <TextField key={field} select label={t(`crew.${field}`)} value={competition.representativeFields[field]} onChange={e => setCompetition({...competition, representativeFields: {...competition.representativeFields, [field]: e.target.value as Policy}})}>{(['HIDDEN', 'OPTIONAL', 'REQUIRED'] as const).map(policy => <MenuItem key={policy} value={policy}>{t(`crew.policy.${policy}`)}</MenuItem>)}</TextField>)}
{requirements.length === 0 && <Typography>{t('wizard.noRequirements')}
      </Typography>}<FormControlLabel label={t('competition.admission')} control={<Checkbox checked={competition.admissionConfirmationRequired} onChange={e => setCompetition({...competition, admissionConfirmationRequired: e.target.checked})}/>} />
      <Button onClick={() => setRequirements([...requirements, {name: '', valueType: 'YES_NO', required: false, displayOrder: requirements.length}])}>{t('wizard.addRequirement')}
      </Button>{requirements.map((r, i) => <Box key={i} sx={{display: 'flex', gap: 1, flexWrap: 'wrap'}}>
      <TextField id={`requirements[${i}].name`} label={t('wizard.requirementName')} value={r.name} onChange={e => setRequirements(requirements.map((x, n) => n === i ? {...x, name: e.target.value} : x))}/>
      <TextField select label={t('wizard.valueType')} value={r.valueType} onChange={e => setRequirements(requirements.map((x, n) => n === i ? {...x, valueType: e.target.value} : x))}>{['YES_NO', 'TEXT', 'DATE', 'NUMBER'].map(type => <MenuItem key={type} value={type}>{t(('wizard.valueTypes.' + type) as 'wizard.valueTypes.TEXT')}
      </MenuItem>)}
      </TextField>
      <FormControlLabel label={t('wizard.required')} control={<Checkbox checked={r.required} onChange={e => setRequirements(requirements.map((x, n) => n === i ? {...x, required: e.target.checked} : x))}/>}/>
      <Button onClick={() => setRequirements(requirements.filter((_, n) => n !== i).map((x, n) => ({...x, displayOrder: n})))}>{t('wizard.remove')}
      </Button>
      </Box>)}
      </>}
    {step === 3 && <>
      <Box sx={{display: 'flex', alignItems: 'center'}}><Tabs value={disciplines.length ? activeDiscipline : false} onChange={(_, value: number) => setActiveDiscipline(value)} variant="scrollable" aria-label={t('wizard.step3')}>
        {disciplines.map((d, i) => <Tab key={i} label={disciplineName(d)} id={`discipline-tab-${i}`} aria-controls={`discipline-panel-${i}`}/>)}</Tabs>
        {disciplines.length > 0 && <IconButton aria-label={t('wizard.addDiscipline')} onClick={() => {setActiveDiscipline(disciplines.length); setDisciplines([...disciplines, freshDiscipline(disciplines.length)])}}>+</IconButton>}</Box>
      {disciplines.length === 0 && <Box role="tabpanel" sx={{border: 1, p: 2}}><Typography>{t('wizard.noDisciplines')}</Typography><Button onClick={() => {setActiveDiscipline(0); setDisciplines([freshDiscipline(0)])}}>{t('wizard.addDiscipline')}</Button></Box>}
      {disciplines.map((d, i) => <Box key={i} role="tabpanel" id={`discipline-panel-${i}`} aria-labelledby={`discipline-tab-${i}`} hidden={activeDiscipline !== i} sx={{'&:not([hidden])': {display: 'grid'}, gap: 1, border: 1, p: 2}}>
      <TextField id={`disciplines[${i}].name`} label={t('discipline.name')} value={d.name} onChange={e => updateDiscipline(i, {name: e.target.value})}/>
      <TextField select label={t('discipline.type')} value={d.type} onChange={e => {const type = e.target.value; updateDiscipline(i, {type, gateCount: type === 'SLALOM' ? 1 : null, attemptCount: type === 'SLALOM' ? 2 : null, qualificationDisciplineIndex: null})}}>{types.map(type => <MenuItem key={type} value={type}>{t(('discipline.types.' + type) as 'discipline.types.SPRINT')}
      </MenuItem>)}
      </TextField>{categories.map((c, n) => <FormControlLabel key={n} label={`${c.classLabel} / ${c.scoringGroupLabel}`} control={<Checkbox checked={d.categoryIndexes.includes(n)} onChange={e => updateDiscipline(i, {categoryIndexes: e.target.checked ? [...d.categoryIndexes, n] : d.categoryIndexes.filter(x => x !== n)})}/>}/>)}{d.type === 'SLALOM' && <>
      <TextField id={`disciplines[${i}].gateCount`} type="number" label={t('discipline.gateCount')} value={d.gateCount ?? ''} onChange={e => updateDiscipline(i, {gateCount: Number(e.target.value)})}/>
      <TextField id={`disciplines[${i}].attemptCount`} type="number" label={t('discipline.attemptCount')} value={d.attemptCount ?? ''} onChange={e => updateDiscipline(i, {attemptCount: Number(e.target.value)})}/>
      </>}{d.type === 'PARALLEL_SPRINT' && <TextField select id={`disciplines[${i}].qualificationDisciplineIndex`} label={t('discipline.qualification')} value={d.qualificationDisciplineIndex ?? ''} onChange={e => updateDiscipline(i, {qualificationDisciplineIndex: e.target.value === '' ? null : Number(e.target.value)})}>
      <MenuItem value="">{t('discipline.automatic')}
      </MenuItem>{disciplines.map((other, n) => n !== i && other.type !== 'PARALLEL_SPRINT' && d.categoryIndexes.every(x => other.categoryIndexes.includes(x)) ? <MenuItem key={n} value={n}>{disciplineName(other)}</MenuItem> : null)}
      </TextField>}
      <Button onClick={() => updateDiscipline(i, {pointsTable: {name: '', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: []}})}>{t('wizard.blankPoints')}
      </Button>
      <Button onClick={() => applyPreset(i, d)}>{t('wizard.preset')}
      </Button>
      <TextField select label={t('wizard.copyPoints')} value="" onChange={e => {const source = disciplines[Number(e.target.value)].pointsTable; if (source) updateDiscipline(i, {pointsTable: structuredClone(source)})}}>{disciplines.map((x, n) => n !== i && x.pointsTable ? <MenuItem key={n} value={n}>{disciplineName(x)}</MenuItem> : null)}
      </TextField>{d.pointsTable && <><Typography>{d.pointsTable.name}</Typography><Button onClick={() => setExpandedPoints({...expandedPoints, [i]: !expandedPoints[i]})}>{t('wizard.configurePoints')}</Button>{expandedPoints[i] && <>
      <TextField label={t('wizard.tableName')} value={d.pointsTable.name} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, name: e.target.value}})}/>{d.pointsTable.rows.map((row, n) => <Box key={n} sx={{display: 'flex', gap: 1}}>
      <TextField id={`disciplines[${i}].pointsTable.rows[${n}].place`} type="number" label={t('wizard.place')} value={row.place} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.map((x, k) => k === n ? {...x, place: Number(e.target.value)} : x)}})}/>
      <TextField id={`disciplines[${i}].pointsTable.rows[${n}].points`} type="number" label={t('wizard.points')} value={row.points} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.map((x, k) => k === n ? {...x, points: Number(e.target.value)} : x)}})}/>
      <Button onClick={() => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.filter((_, k) => k !== n)}})}>{t('wizard.remove')}
      </Button>
      </Box>)}
      <Button onClick={() => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: [...d.pointsTable!.rows, {place: d.pointsTable!.rows.length + 1, points: 0}]}})}>{t('wizard.addRow')}
      </Button>
      </>}</>}
      <Button onClick={() => {setActiveDiscipline(Math.max(0, Math.min(i, disciplines.length - 2))); setExpandedPoints(Object.fromEntries(Object.entries(expandedPoints).filter(([key]) => Number(key) !== i).map(([key, value]) => [Number(key) > i ? Number(key) - 1 : Number(key), value]))); setDisciplines(disciplines.filter((_, n) => n !== i).map((x, n) => ({...x, displayOrder: n, qualificationDisciplineIndex: x.qualificationDisciplineIndex === i ? null : x.qualificationDisciplineIndex !== null && x.qualificationDisciplineIndex > i ? x.qualificationDisciplineIndex - 1 : x.qualificationDisciplineIndex}))); setTiebreak(tiebreak === i ? null : tiebreak !== null && tiebreak > i ? tiebreak - 1 : tiebreak)}}>{t('wizard.remove')}
      </Button>
      </Box>)}
      <TextField select label={t('wizard.tiebreak')} value={tiebreak ?? ''} onChange={e => {setTiebreakExplicit(true); setTiebreak(e.target.value === '' ? null : Number(e.target.value))}}>
      <MenuItem value="">{t('discipline.noPoints')}
      </MenuItem>{disciplines.map((d, i) => <MenuItem key={i} value={i}>{disciplineName(d)}</MenuItem>)}
      </TextField>
      </>}
    {step === 4 && <>
      <Typography>{competition.title} · {displayDate(competition.startDate)} – {displayDate(competition.endDate)} · {competition.location} · {competition.timeZone}</Typography>
      <Typography>{competition.description}</Typography>
      <Typography>{t('competition.admission')}: {t(competition.admissionConfirmationRequired ? 'competition.yes' : 'competition.no')}
      </Typography>
      <Typography>{t('wizard.step1')}: {categories.map(c => `${c.classLabel} / ${c.scoringGroupLabel}`).join(', ') || '—'}</Typography>
      {categories.map((c, i) => <Typography key={i}>{c.classLabel} / {c.scoringGroupLabel}: {Array.from({length: c.crewSize}, (_, n) => c.seatLabels?.[n] || categoryT('seat', {number: n + 1})).join(', ')}</Typography>)}
      <Button onClick={() => setStep(1)}>{t('competition.edit')}</Button>
      <Typography>{t('crew.representativeSettings')}: {(['representative', 'phone', 'whatsapp', 'telegram', 'email'] as const).map(field => `${t(`crew.${field}`)}: ${t(`crew.policy.${competition.representativeFields[field]}`)}`).join(', ')}</Typography><Button onClick={() => setStep(2)}>{t('competition.edit')}</Button>
      <Typography>{t('wizard.step2')}: {requirements.map(r => `${r.name} (${t(('wizard.valueTypes.' + r.valueType) as 'wizard.valueTypes.TEXT')}, ${t(r.required ? 'competition.yes' : 'competition.no')})`).join(', ') || '—'}</Typography>{disciplines.map((d, i) => <Box key={i}>
      <Typography>{disciplineName(d)}{d.categoryIndexes.length > 0 && ` · ${d.categoryIndexes.map(n => categories[n] ? `${categories[n].classLabel} / ${categories[n].scoringGroupLabel}` : '').join(', ')}`}{!!d.gateCount && ` · ${t('discipline.gateCount')}: ${d.gateCount}`}{d.type !== 'PARALLEL_SPRINT' && ` · ${t('discipline.attemptCount')}: ${d.type === 'SLALOM' ? d.attemptCount : 1}`}{d.qualificationDisciplineIndex != null && disciplines[d.qualificationDisciplineIndex] && ` · ${disciplineName(disciplines[d.qualificationDisciplineIndex])}`}</Typography>
      <Typography>{d.pointsTable?.name}: {d.pointsTable?.rows.map(r => `${r.place}: ${r.points}`).join(', ')}
      </Typography>
      </Box>)}
      <Typography>{t('wizard.tiebreak')}: {tiebreak != null && disciplines[tiebreak] ? disciplineName(disciplines[tiebreak]) : '—'}</Typography>
      </>}
    <Box sx={{position: 'sticky', bottom: 0, backgroundColor: 'background.paper', py: 1, zIndex: 4}}>{step > 0 && <Button disabled={busy} onClick={() => {setStep(step - 1); setError('')}}>{t('competition.previous')}
      </Button>}{step < 4 ? <Button onClick={next}>{t('competition.next')}
      </Button> : <Button disabled={busy} onClick={() => void save()}>{t('competition.save')}
      </Button>}<Button disabled={busy} onClick={onCancel}>{t('competition.cancel')}
      </Button>
      </Box>
  </Box>
}
