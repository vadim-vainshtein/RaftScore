import {useEffect, useState} from 'react'
import {Box, Button, Dialog, DialogActions, DialogTitle, ListSubheader, MenuItem, Tab, Table as MuiTable, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey} from './api/errors'

export type PointsValue = {name: string, tiePolicy: string, rows: {place: number, points: number}[], catalogId?: number, catalogVersion?: number, catalogType?: string}
type Table = {id: number, name: string, disciplineType: string, version: number, preset: boolean, rows: {place: number, points: number}[]}
const types = ['SPRINT', 'PARALLEL_SPRINT', 'SLALOM', 'LONG_RACE']

export default function PointsCatalog({disciplineType, value, onChange, onPreset, onEditingChange}: {disciplineType?: string, value?: PointsValue | null, onChange?: (v: PointsValue | null) => void, onPreset?: () => void, onEditingChange?: (editing: boolean) => void}) {
  const {t} = useTranslation()
  const [tables, setTables] = useState<Table[]>([]), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState(false), [draft, setDraft] = useState<PointsValue | null>(null)
  const changeEditing = (next: boolean) => {setEditing(next); onEditingChange?.(next)}
  const [activeType, setActiveType] = useState('SPRINT'), [saveDialog, setSaveDialog] = useState(false)
  const [localValue, setLocalValue] = useState<PointsValue | null>(null)
  const selected = onChange ? value : localValue
  const choose = (next: PointsValue | null) => {if (onChange) onChange(next); else setLocalValue(next)}
  useEffect(() => {let active = true; const refresh = () => {apiRequest<Table[]>('/api/points-catalog').then(items => {if (active) setTables(Array.isArray(items) ? items : [])}).catch(e => {if (active) setError(apiErrorKey(e))})}; refresh(); window.addEventListener('points-catalog-changed', refresh); return () => {active = false; window.removeEventListener('points-catalog-changed', refresh)}}, [])
  const load = (item: Table): PointsValue => ({name: item.name, tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: structuredClone(item.rows), catalogId: item.id, catalogVersion: item.version, catalogType: item.disciplineType})
  const save = async (copy: boolean) => {
    if (!draft) return
    setSaveDialog(false)
    if (!draft.name.trim() || draft.name.length > 200 || draft.rows.some(r => !Number.isInteger(r.place) || r.place < 1 || !Number.isInteger(r.points) || r.points < 0) || new Set(draft.rows.map(r => r.place)).size !== draft.rows.length) {setError('pointsCatalog.invalid'); return}
    setBusy(true); setError('')
    try {
      const body = {name: draft.name, disciplineType: draft.catalogType ?? disciplineType ?? 'SPRINT', rows: draft.rows, version: draft.catalogVersion}
      const result = await csrfRequest<Table>(copy || !draft.catalogId ? '/api/points-catalog' : `/api/points-catalog/${draft.catalogId}`, copy || !draft.catalogId ? 'POST' : 'PUT', body)
      setTables(previous => [...previous.filter(x => x.id !== result.id), result]); setActiveType(result.disciplineType); choose(load(result)); changeEditing(false); window.dispatchEvent(new Event('points-catalog-changed'))
    } catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  const groups = disciplineType ? [disciplineType, ...types.filter(type => type !== disciplineType)] : types
  const label = (item: Table) => item.name + (tables.filter(x => x.name === item.name && x.disciplineType === item.disciplineType).length > 1 ? ` #${item.id}` : '')
  const selectedId = selected?.catalogId && tables.some(x => x.id === selected.catalogId && x.version === selected.catalogVersion) ? String(selected.catalogId) : selected ? 'snapshot' : ''
  const rowInput = (row: PointsValue['rows'][number], index: number, field: 'place' | 'points') =>
    <TextField disabled={busy} type="number" label={t(field === 'place' ? 'wizard.place' : 'wizard.points')} value={row[field]}
      onChange={e => setDraft(current => current && ({...current, rows: current.rows.map((item, i) => i === index ? {...item, [field]: Number(e.target.value)} : item)}))}/>
  const removeRow = (index: number) => setDraft(current => current && ({...current, rows: current.rows.filter((_, i) => i !== index)}))
  const editor = editing && draft && <>
    {!onChange && <TextField disabled={busy} label={t('wizard.tableName')} value={draft.name} onChange={e => setDraft({...draft, name: e.target.value})}/>}
    {onChange ? <>
      {draft.catalogId && <Button disabled={busy} onClick={() => void save(false)}>{t('pointsCatalog.saveChanges')}</Button>}
      <Button disabled={busy} onClick={() => void save(true)}>{t(draft.catalogId ? 'pointsCatalog.saveNew' : 'pointsCatalog.create')}</Button>
      <Button disabled={busy} onClick={() => changeEditing(false)}>{t('crew.cancel')}</Button>
    </> : <Box>
      <Button disabled={busy} onClick={() => draft.catalogId ? setSaveDialog(true) : void save(true)}>{t('pointsCatalog.save')}</Button>
      <Button disabled={busy} onClick={() => {changeEditing(false); setError('')}}>{t('pointsCatalog.cancelEdit')}</Button>
      <Dialog open={saveDialog} onClose={() => setSaveDialog(false)}>
        <DialogTitle>{t('pointsCatalog.saveChoice')}</DialogTitle>
        <DialogActions>
          <Button onClick={() => setSaveDialog(false)}>{t('crew.cancel')}</Button>
          <Button onClick={() => void save(false)}>{t('pointsCatalog.saveChanges')}</Button>
          <Button onClick={() => void save(true)}>{t('pointsCatalog.saveNew')}</Button>
        </DialogActions>
      </Dialog>
    </Box>}
    {onChange && <TextField disabled={busy} label={t('wizard.tableName')} value={draft.name} onChange={e => setDraft({...draft, name: e.target.value})}/>}
    {onChange && <TextField disabled={busy} select label={t('discipline.type')} value={draft.catalogType ?? disciplineType ?? 'SPRINT'} onChange={e => setDraft({...draft, catalogType: e.target.value})}>
      {types.map(type => <MenuItem key={type} value={type}>{t(`discipline.types.${type}` as 'discipline.types.SPRINT')}</MenuItem>)}
    </TextField>}
    {onChange ? draft.rows.map((row, index) => <Box key={index} sx={{display: 'flex', gap: 1}}>
      {rowInput(row, index, 'place')}{rowInput(row, index, 'points')}
      <Button disabled={busy} onClick={() => removeRow(index)}>{t('wizard.remove')}</Button>
    </Box>) : <MuiTable size="small" sx={{tableLayout: 'fixed', '& .MuiTextField-root': {width: '100%'}, '& .MuiTableCell-root': {px: {xs: 0.5, sm: 2}}}}>
      <TableHead><TableRow><TableCell>{t('wizard.place')}</TableCell><TableCell>{t('wizard.points')}</TableCell><TableCell /></TableRow></TableHead>
      <TableBody>{draft.rows.map((row, index) => <TableRow key={index}>
        <TableCell>{rowInput(row, index, 'place')}</TableCell>
        <TableCell>{rowInput(row, index, 'points')}</TableCell>
        <TableCell><Button disabled={busy} onClick={() => removeRow(index)}>{t('wizard.remove')}</Button></TableCell>
      </TableRow>)}</TableBody>
    </MuiTable>}
    <Button disabled={busy} onClick={() => setDraft({...draft, rows: [...draft.rows, {place: Math.max(0, ...draft.rows.map(r => r.place)) + 1, points: 0}]})}>{t('wizard.addRow')}</Button>
  </>
  return <Box sx={{display: 'grid', gap: 1}}>
    {error && <Typography role="alert" color="error">{t(error as 'apiError.network')}</Typography>}
    {!onChange && <>
      <Tabs value={activeType} onChange={(_, type: string) => {setActiveType(type); choose(null); setError('')}} variant="scrollable" scrollButtons="auto" aria-label={t('discipline.type')}>
        {types.map(type => <Tab key={type} value={type} label={t(`discipline.types.${type}` as 'discipline.types.SPRINT')} disabled={editing || busy} id={`points-tab-${type}`} aria-controls={`points-panel-${type}`}/>)}
      </Tabs>
      {types.map(type => <Box key={type} role="tabpanel" hidden={type !== activeType} id={`points-panel-${type}`} aria-labelledby={`points-tab-${type}`} sx={{display: type === activeType ? 'grid' : 'none', gap: 1}}>
        {type === activeType && !selected && !editing && <>
          {tables.filter(item => item.disciplineType === activeType).sort((a, b) => a.name.localeCompare(b.name)).map(item => <Button key={item.id} onClick={() => {choose(load(item)); setError('')}}>{label(item)}</Button>)}
          <Button onClick={() => {setDraft({name: '', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: [], catalogType: activeType}); setError(''); changeEditing(true)}}>{t('pointsCatalog.create')}</Button>
        </>}
        {type === activeType && selected && !editing && <>
          <Typography variant="h6">{selected.name}</Typography>
          <Box><Button onClick={() => {setDraft(structuredClone(selected)); setError(''); changeEditing(true)}}>{t('pointsCatalog.edit')}</Button><Button onClick={() => {choose(null); setError('')}}>{t('pointsCatalog.back')}</Button></Box>
          <MuiTable size="small"><TableHead><TableRow><TableCell>{t('wizard.place')}</TableCell><TableCell>{t('wizard.points')}</TableCell></TableRow></TableHead><TableBody>{selected.rows.map(row => <TableRow key={row.place}><TableCell>{row.place}</TableCell><TableCell>{row.points}</TableCell></TableRow>)}</TableBody></MuiTable>
        </>}
        {type === activeType && editor}
      </Box>)}
    </>}
    {onChange && <>
    <TextField select disabled={editing} label={t('pointsCatalog.select')} slotProps={{select: {displayEmpty: true}, inputLabel: {shrink: true}}} value={selectedId} onChange={e => {
      if (e.target.value === 'new') {setDraft({name: '', tiePolicy: 'SHARED_PLACE_SAME_POINTS', rows: [], catalogType: disciplineType ?? 'SPRINT'}); changeEditing(true); return}
      if (!e.target.value) {choose(null); changeEditing(false); return}
      const item = tables.find(x => String(x.id) === e.target.value); if (item) {choose(load(item)); changeEditing(false); if (item.preset) onPreset?.()}
    }}>
      <MenuItem value="">{t('pointsCatalog.none')}</MenuItem><MenuItem value="new">{t('wizard.blankPoints')}</MenuItem>
      {selectedId === 'snapshot' && <MenuItem value="snapshot">{selected?.name || t('pointsCatalog.unsaved')} ({t('pointsCatalog.snapshot')})</MenuItem>}
      {groups.flatMap(type => {const group = tables.filter(item => item.disciplineType === type).sort((a, b) => a.name.localeCompare(b.name)); return group.length ? [<ListSubheader key={`group-${type}`}>{t(`discipline.types.${type}` as 'discipline.types.SPRINT')}</ListSubheader>, ...group.map(item => <MenuItem key={item.id} value={String(item.id)}>{label(item)}</MenuItem>)] : []})}
    </TextField>
    {selected && !editing && <Button onClick={() => {setDraft(structuredClone(selected)); changeEditing(true)}}>{t('wizard.configurePoints')}</Button>}
    </>}
    {onChange && editor}
  </Box>
}
