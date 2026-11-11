import {useEffect, useRef, useState} from 'react'
import {Box, Button, Checkbox, FormControlLabel, MenuItem, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey, fieldErrorKey} from './api/errors'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type FieldIssue = {step: number, field: string, label: string, message?: string}
type Competition = {title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}
type Template = {id: number, classLabel: string, scoringGroupLabel: string, crewSize: number, displayName: string, archived: boolean}
type Category = {templateId?: number, classLabel: string, scoringGroupLabel: string, crewSize: number}
type Requirement = {name: string, valueType: string, required: boolean, displayOrder: number}
type Row = {place: number, points: number}
type Discipline = {name: string, type: string, displayOrder: number, categoryIndexes: number[], gateCount: number | null, attemptCount: number | null, qualificationDisciplineIndex: number | null, pointsTable: {name: string, tiePolicy: string, rows: Row[]} | null}
const types = ['SPRINT', 'PARALLEL_SPRINT', 'SLALOM', 'LONG_RACE']
const multipliers = [1, 2, 3, 4]
const freshDiscipline = (order: number): Discipline => ({name: '', type: 'SPRINT', displayOrder: order, categoryIndexes: [], gateCount: null, attemptCount: null, qualificationDisciplineIndex: null, pointsTable: null})

export default function CompetitionWizard({onCancel, onSaved}: {onCancel: () => void, onSaved: (value: {id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}) => void}) {
  const {t, i18n} = useTranslation()
  const displayDate = (value: string) => value ? formatDate(new Date(`${value}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC') : '—'
  const [step, setStep] = useState(0), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [competition, setCompetition] = useState<Competition>({title: '', startDate: '', endDate: '', location: '', timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', description: '', admissionConfirmationRequired: false})
  const [templates, setTemplates] = useState<Template[]>([]), [categories, setCategories] = useState<Category[]>([])
  const [requirements, setRequirements] = useState<Requirement[]>([]), [disciplines, setDisciplines] = useState<Discipline[]>([])
  const [issues, setIssues] = useState<FieldIssue[]>([])
  const [focusField, setFocusField] = useState<{field: string} | null>(null)
  const handledFocus = useRef<typeof focusField>(null)
  useEffect(() => { if (focusField && focusField !== handledFocus.current) { document.getElementById(focusField.field)?.focus(); handledFocus.current = focusField } }, [step, focusField])
  const [explicitTiebreak, setTiebreak] = useState<number | null>(null)
  const [presetSelected, setPresetSelected] = useState(false)
  const [tiebreakExplicit, setTiebreakExplicit] = useState(false)
  const slalom = disciplines.findIndex(d => d.type === 'SLALOM')
  const tiebreak = presetSelected && !tiebreakExplicit ? (slalom < 0 ? null : slalom) : explicitTiebreak
  useEffect(() => {let active = true; (async () => {try {let page = 0, total = 1; const all: Template[] = []; while (page < total) {const result = await apiRequest<{content: Template[], totalPages: number}>(`/api/category-templates?archived=false&page=${page}&size=100`); all.push(...result.content.filter(item => !item.archived)); total = result.totalPages; page++} if (active) setTemplates(all)} catch {if (active) setError('wizard.templatesError')}})(); return () => {active = false}}, [t])
  const updateDiscipline = (i: number, patch: Partial<Discipline>) => setDisciplines(items => items.map((d, index) => index === i ? {...d, ...patch} : d))
  const fieldIssues = (currentStep: number): FieldIssue[] => {
    const found: FieldIssue[] = []
    const add = (field: string, label: string) => found.push({step: currentStep, field, label})
    if (currentStep === 0) {
      if (!competition.title.trim()) add('competition.title', t('competition.title'))
      if (!competition.startDate) add('competition.startDate', t('competition.startDate'))
      if (competition.endDate && competition.endDate < competition.startDate) add('competition.endDate', t('competition.endDate'))
      if (!competition.location.trim()) add('competition.location', t('competition.location'))
      try { new Intl.DateTimeFormat('en', {timeZone: competition.timeZone}) } catch { add('competition.timeZone', t('competition.timeZone')) }
    }
    if (currentStep === 1) categories.forEach((c, i) => {
      if (!c.classLabel.trim()) add(`categories[${i}].classLabel`, t('wizard.classLabel'))
      if (!c.scoringGroupLabel.trim()) add(`categories[${i}].scoringGroupLabel`, t('wizard.scoringGroupLabel'))
      if (!Number.isInteger(c.crewSize) || c.crewSize < 1) add(`categories[${i}].crewSize`, t('wizard.crewSize'))
    })
    if (currentStep === 2) requirements.forEach((r, i) => { if (!r.name.trim()) add(`requirements[${i}].name`, t('wizard.requirementName')) })
    if (currentStep === 3) disciplines.forEach((d, i) => {
      if (!d.name.trim()) add(`disciplines[${i}].name`, t('discipline.name'))
      if (d.type === 'SLALOM') {
        if (!Number.isInteger(d.gateCount) || (d.gateCount ?? 0) < 1) add(`disciplines[${i}].gateCount`, t('discipline.gateCount'))
        if (!Number.isInteger(d.attemptCount) || (d.attemptCount ?? 0) < 1) add(`disciplines[${i}].attemptCount`, t('discipline.attemptCount'))
      }
      if (d.qualificationDisciplineIndex !== null && (!disciplines[d.qualificationDisciplineIndex] || disciplines[d.qualificationDisciplineIndex].type === 'PARALLEL_SPRINT' || !d.categoryIndexes.every(x => disciplines[d.qualificationDisciplineIndex!].categoryIndexes.includes(x)))) add(`disciplines[${i}].qualificationDisciplineIndex`, t('discipline.qualification'))
      d.pointsTable?.rows.forEach((r, n) => {
        if (!Number.isInteger(r.place) || r.place < 1 || d.pointsTable!.rows.filter(x => x.place === r.place).length > 1) add(`disciplines[${i}].pointsTable.rows[${n}].place`, t('wizard.place'))
        if (!Number.isInteger(r.points) || r.points < 0) add(`disciplines[${i}].pointsTable.rows[${n}].points`, t('wizard.points'))
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
  const save = async () => {setBusy(true); setError(''); try {const result = await csrfRequest<{id: number, version: number, title: string, startDate: string, endDate: string, location: string, timeZone: string, description: string | null, admissionConfirmationRequired: boolean}>('/api/competitions/setup', 'POST', {competition: {...competition, endDate: competition.endDate || null}, categories, requirements, disciplines, tiebreakDisciplineIndex: tiebreak}); onSaved(result)} catch (failure) {const found = serverIssues(failure); setIssues(found); if (!found.length) setError(apiErrorKey(failure))} finally {setBusy(false)}}
  return <Box sx={{display: 'grid', gap: 2, maxWidth: 850}}>
    <Typography variant="h5">{t(("wizard.step" + step) as "wizard.step0")}
      </Typography>
      <Typography>{step + 1} / 5</Typography>{error && <Typography role="alert">{t(error as 'apiError.network')}</Typography>}
      {issues.length > 0 && <Box role="alert">{[...new Set(issues.map(issue => issue.step))].map(group => <Box key={group}><Typography>{t(('wizard.step' + group) as 'wizard.step0')}</Typography>{issues.filter(issue => issue.step === group).map((issue, index) => <Box key={index}><Button onClick={() => {setStep(issue.step); setFocusField({field: issue.field})}}>{issue.label}</Button>{issue.message && <Typography component="span">{t(issue.message as 'apiError.required')}</Typography>}</Box>)}</Box>)}</Box>}
    {step === 0 && <>
      <TextField id="competition.title" label={t('competition.title')} value={competition.title} onChange={e => setCompetition({...competition, title: e.target.value})}/>
      <TextField id="competition.startDate" type="date" label={t('competition.startDate')} slotProps={{inputLabel: {shrink: true}}} value={competition.startDate} onChange={e => setCompetition({...competition, startDate: e.target.value})}/>
      <TextField id="competition.endDate" type="date" label={t('competition.endDate')} slotProps={{inputLabel: {shrink: true}}} value={competition.endDate} onChange={e => setCompetition({...competition, endDate: e.target.value})}/>
      <TextField id="competition.location" label={t('competition.location')} value={competition.location} onChange={e => setCompetition({...competition, location: e.target.value})}/>
      <TextField id="competition.timeZone" label={t('competition.timeZone')} value={competition.timeZone} onChange={e => setCompetition({...competition, timeZone: e.target.value})}/>
      <TextField id="competition.description" label={t('competition.description')} multiline value={competition.description ?? ''} onChange={e => setCompetition({...competition, description: e.target.value})}/>
      </>}
    {step === 1 && <>
      <TextField select label={t('wizard.template')} value="" onChange={e => {const item = templates.find(x => x.id === Number(e.target.value)); if (item) setCategories([...categories, {templateId: item.id, classLabel: item.classLabel, scoringGroupLabel: item.scoringGroupLabel, crewSize: item.crewSize}])}}>{templates.map(item => <MenuItem key={item.id} value={item.id}>{item.displayName}</MenuItem>)}
      </TextField>
      <Button onClick={() => setCategories([...categories, {classLabel: '', scoringGroupLabel: '', crewSize: 1}])}>{t('wizard.addCategory')}
      </Button>{categories.map((c, i) => <Box key={i} sx={{display: 'flex', gap: 1, flexWrap: 'wrap'}}>{(['classLabel', 'scoringGroupLabel', 'crewSize'] as const).map(key => <TextField key={key} id={`categories[${i}].${key}`} label={t(`wizard.${key}`)} type={key === 'crewSize' ? 'number' : 'text'} value={c[key]} onChange={e => setCategories(categories.map((item, index) => index === i ? {...item, [key]: key === 'crewSize' ? Number(e.target.value) : e.target.value} : item))}/>)}
      <Button onClick={() => {setCategories(categories.filter((_, index) => index !== i)); setDisciplines(disciplines.map(d => ({...d, categoryIndexes: d.categoryIndexes.filter(x => x !== i).map(x => x > i ? x - 1 : x)})))}}>{t('wizard.remove')}
      </Button>
      <Typography>{c.classLabel} / {c.scoringGroupLabel} ({c.crewSize})</Typography>
      </Box>)}
      </>}
    {step === 2 && <>{requirements.length === 0 && <Typography>{t('wizard.noRequirements')}
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
      <Button onClick={() => setDisciplines([...disciplines, freshDiscipline(disciplines.length)])}>{t('wizard.addDiscipline')}
      </Button>{disciplines.map((d, i) => <Box key={i} sx={{display: 'grid', gap: 1, border: 1, p: 2}}>
      <TextField id={`disciplines[${i}].name`} label={t('discipline.name')} value={d.name} onChange={e => updateDiscipline(i, {name: e.target.value})}/>
      <TextField select label={t('discipline.type')} value={d.type} onChange={e => {const type = e.target.value; updateDiscipline(i, {type, gateCount: type === 'SLALOM' ? 1 : null, attemptCount: type === 'SLALOM' ? 2 : null, qualificationDisciplineIndex: null})}}>{types.map(type => <MenuItem key={type} value={type}>{t(('discipline.types.' + type) as 'discipline.types.SPRINT')}
      </MenuItem>)}
      </TextField>{categories.map((c, n) => <FormControlLabel key={n} label={`${c.classLabel} / ${c.scoringGroupLabel}`} control={<Checkbox checked={d.categoryIndexes.includes(n)} onChange={e => updateDiscipline(i, {categoryIndexes: e.target.checked ? [...d.categoryIndexes, n] : d.categoryIndexes.filter(x => x !== n)})}/>}/>)}{d.type === 'SLALOM' && <>
      <TextField id={`disciplines[${i}].gateCount`} type="number" label={t('discipline.gateCount')} value={d.gateCount ?? ''} onChange={e => updateDiscipline(i, {gateCount: Number(e.target.value)})}/>
      <TextField id={`disciplines[${i}].attemptCount`} type="number" label={t('discipline.attemptCount')} value={d.attemptCount ?? ''} onChange={e => updateDiscipline(i, {attemptCount: Number(e.target.value)})}/>
      </>}{d.type === 'PARALLEL_SPRINT' && <TextField select id={`disciplines[${i}].qualificationDisciplineIndex`} label={t('discipline.qualification')} value={d.qualificationDisciplineIndex ?? ''} onChange={e => updateDiscipline(i, {qualificationDisciplineIndex: e.target.value === '' ? null : Number(e.target.value)})}>
      <MenuItem value="">{t('discipline.automatic')}
      </MenuItem>{disciplines.map((other, n) => n !== i && other.type !== 'PARALLEL_SPRINT' && d.categoryIndexes.every(x => other.categoryIndexes.includes(x)) ? <MenuItem key={n} value={n}>{other.name}</MenuItem> : null)}
      </TextField>}
      <TextField id={`disciplines[${i}].displayOrder`} type="number" label={t('discipline.order')} value={d.displayOrder} onChange={e => updateDiscipline(i, {displayOrder: Number(e.target.value)})}/>
      <Button onClick={() => updateDiscipline(i, {pointsTable: {name: '', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: []}})}>{t('wizard.blankPoints')}
      </Button>
      <Button onClick={() => applyPreset(i, d)}>{t('wizard.preset')}
      </Button>
      <TextField select label={t('wizard.copyPoints')} value="" onChange={e => {const source = disciplines[Number(e.target.value)].pointsTable; if (source) updateDiscipline(i, {pointsTable: structuredClone(source)})}}>{disciplines.map((x, n) => n !== i && x.pointsTable ? <MenuItem key={n} value={n}>{x.name}</MenuItem> : null)}
      </TextField>{d.pointsTable && <>
      <TextField label={t('wizard.tableName')} value={d.pointsTable.name} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, name: e.target.value}})}/>{d.pointsTable.rows.map((row, n) => <Box key={n} sx={{display: 'flex', gap: 1}}>
      <TextField id={`disciplines[${i}].pointsTable.rows[${n}].place`} type="number" label={t('wizard.place')} value={row.place} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.map((x, k) => k === n ? {...x, place: Number(e.target.value)} : x)}})}/>
      <TextField id={`disciplines[${i}].pointsTable.rows[${n}].points`} type="number" label={t('wizard.points')} value={row.points} onChange={e => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.map((x, k) => k === n ? {...x, points: Number(e.target.value)} : x)}})}/>
      <Button onClick={() => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: d.pointsTable!.rows.filter((_, k) => k !== n)}})}>{t('wizard.remove')}
      </Button>
      </Box>)}
      <Button onClick={() => updateDiscipline(i, {pointsTable: {...d.pointsTable!, rows: [...d.pointsTable!.rows, {place: d.pointsTable!.rows.length + 1, points: 0}]}})}>{t('wizard.addRow')}
      </Button>
      </>}
      <Button onClick={() => {setDisciplines(disciplines.filter((_, n) => n !== i).map((x, n) => ({...x, displayOrder: n, qualificationDisciplineIndex: x.qualificationDisciplineIndex === i ? null : x.qualificationDisciplineIndex !== null && x.qualificationDisciplineIndex > i ? x.qualificationDisciplineIndex - 1 : x.qualificationDisciplineIndex}))); setTiebreak(tiebreak === i ? null : tiebreak !== null && tiebreak > i ? tiebreak - 1 : tiebreak)}}>{t('wizard.remove')}
      </Button>
      </Box>)}
      <TextField select label={t('wizard.tiebreak')} value={tiebreak ?? ''} onChange={e => {setTiebreakExplicit(true); setTiebreak(e.target.value === '' ? null : Number(e.target.value))}}>
      <MenuItem value="">{t('discipline.noPoints')}
      </MenuItem>{disciplines.map((d, i) => <MenuItem key={i} value={i}>{d.name}</MenuItem>)}
      </TextField>
      </>}
    {step === 4 && <>
      <Typography>{competition.title} · {displayDate(competition.startDate)} – {displayDate(competition.endDate)} · {competition.location} · {competition.timeZone}</Typography>
      <Typography>{competition.description}</Typography>
      <Typography>{t('competition.admission')}: {t(competition.admissionConfirmationRequired ? 'competition.yes' : 'competition.no')}
      </Typography>
      <Typography>{t('wizard.step1')}: {categories.map(c => `${c.classLabel} / ${c.scoringGroupLabel} (${c.crewSize})`).join(', ') || '—'}</Typography>
      <Typography>{t('wizard.step2')}: {requirements.map(r => `${r.name} (${t(('wizard.valueTypes.' + r.valueType) as 'wizard.valueTypes.TEXT')}, ${t(r.required ? 'competition.yes' : 'competition.no')})`).join(', ') || '—'}</Typography>{disciplines.map((d, i) => <Box key={i}>
      <Typography>{d.name} · {t(('discipline.types.' + d.type) as 'discipline.types.SPRINT')} · {d.categoryIndexes.map(n => categories[n] ? `${categories[n].classLabel} / ${categories[n].scoringGroupLabel}` : '').join(', ')} · {t('discipline.order')}: {d.displayOrder} · {d.gateCount ?? '—'} / {d.attemptCount ?? '—'} · {disciplines[d.qualificationDisciplineIndex ?? -1]?.name}</Typography>
      <Typography>{d.pointsTable?.name}: {d.pointsTable?.rows.map(r => `${r.place}: ${r.points}`).join(', ')}
      </Typography>
      </Box>)}
      <Typography>{t('wizard.tiebreak')}: {disciplines[tiebreak ?? -1]?.name || '—'}</Typography>
      </>}
    <Box>{step > 0 && <Button disabled={busy} onClick={() => {setStep(step - 1); setError('')}}>{t('competition.previous')}
      </Button>}{step < 4 ? <Button onClick={next}>{t('competition.next')}
      </Button> : <Button disabled={busy} onClick={() => void save()}>{t('competition.save')}
      </Button>}<Button disabled={busy} onClick={onCancel}>{t('competition.cancel')}
      </Button>
      </Box>
  </Box>
}
