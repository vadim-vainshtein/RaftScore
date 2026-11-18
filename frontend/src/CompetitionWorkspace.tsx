import {useEffect, useRef, useState} from 'react'
import {Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import StartLists from './StartLists'
import Competitions from './Competitions'
import Crews from './Crews'
import Disciplines from './Disciplines'
import Results from './Results'
import ResultGrid from './ResultGrid'
import ParallelSprint from './ParallelSprint'
import {apiRequest, csrfRequest} from './api/client'
import {formatDate} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Competition = {id: number, title: string, version: number, startDate?: string, endDate?: string | null, location?: string, status: 'CREATED' | 'STARTED' | 'COMPLETED'}
export default function CompetitionWorkspace({path, readOnly}: {path: string, readOnly: boolean}) {
  const {t, i18n} = useTranslation()
  const id = /^\/workspace\/(\d+)(?:\/(results|result-grid|parallel-sprint|start-lists|crews|disciplines|edit))?(?:\?.*)?$/.exec(path)?.[1]
  const [competition, setCompetition] = useState<Competition | null>(null)
  const [error, setError] = useState(false)
  const [completionError, setCompletionError] = useState(false)
  const [completing, setCompleting] = useState(false)
  const [refreshingCompetition, setRefreshingCompetition] = useState(false), [competitionRefreshFailed, setCompetitionRefreshFailed] = useState(false)
  const intentionalNavigation = useRef(false)
  const [nextRoute, setNextRoute] = useState<string | null>(null)
  useEffect(() => {if (!completing && nextRoute) {window.location.hash = nextRoute}}, [completing, nextRoute])
  const [disciplineTypes, setDisciplineTypes] = useState<{id: number, type: string}[] | null>(null)
  const [readiness, setReadiness] = useState<{categories: number, disciplines: number} | null>(null)
  const [crewRevision, setCrewRevision] = useState(0)
  const [disciplineRevision, setDisciplineRevision] = useState(0)
  const [startRevision, setStartRevision] = useState(0)
  const [crewReadiness, setCrewReadiness] = useState<{total: number, admitted: number} | null>(null)
  const [startReadiness, setStartReadiness] = useState<number | null>(null)
  const [query, setQuery] = useState(() => window.location.hash.includes('?') ? '?' + window.location.hash.split('?')[1] : '')
  useEffect(() => {const listener = () => setQuery(window.location.hash.includes('?') ? '?' + window.location.hash.split('?')[1] : ''); window.addEventListener('workspace-filter-change', listener); return () => window.removeEventListener('workspace-filter-change', listener)}, [])
  useEffect(() => {if (!id || readOnly) return; let active = true; apiRequest<{eligible: boolean}[]>(`/api/competitions/${id}/entries/private`).then(crews => {if (active) setCrewReadiness({total: crews.length, admitted: crews.filter(c => c.eligible).length})}).catch(() => {}); return () => {active = false}}, [id, readOnly, crewRevision])
  useEffect(() => {const params = new URLSearchParams(query); const discipline = params.get('discipline'), category = params.get('category'); if (!id || !discipline || !category || !disciplineTypes?.some(d => String(d.id) === discipline && (!path.includes('/result-grid') || ['SPRINT', 'SLALOM', 'LONG_RACE'].includes(d.type)) && (!path.includes('/parallel-sprint') || d.type === 'PARALLEL_SPRINT'))) return; let active = true; apiRequest<{slots: unknown[]}>(`/api/competitions/${id}/disciplines/${discipline}/categories/${category}/start-list`).then(list => {if (active) setStartReadiness(list.slots.length)}).catch(() => {}); return () => {active = false}}, [id, query, disciplineTypes, path, startRevision])
  const [completionDrafts, setCompletionDrafts] = useState<number | null>(null)
  useEffect(() => {let active = true; if (!id) return; apiRequest<Competition>(`/api/competitions/${id}`).then(c => {if (active) {setCompetition(c); const params = new URLSearchParams(path.split('?')[1] ?? ''); if (!readOnly && c.status === 'STARTED' && params.get('complete') === '1') {params.delete('complete'); const cleanQuery = params.size ? '?' + params : ''; window.history.replaceState(window.history.state, '', `#/workspace/${id}${cleanQuery}`); setQuery(cleanQuery); setCompletionDrafts(0)}}}).catch(() => {if (active) setError(true)}); return () => {active = false}}, [id, path, readOnly])
  useEffect(() => {if (!id) return; let active = true; Promise.all([apiRequest<unknown[]>(`/api/competitions/${id}/categories`), apiRequest<{id: number, type: string}[]>(`/api/competitions/${id}/disciplines`)]).then(([categories, disciplines]) => {if (active) {setReadiness({categories: categories.length, disciplines: disciplines.length}); setDisciplineTypes(disciplines)}}).catch(() => {}); return () => {active = false}}, [id, disciplineRevision])
  useEffect(() => {
    const blockNavigation = (event: Event) => {if (completing && !intentionalNavigation.current) event.preventDefault()}
    window.addEventListener('category-navigation', blockNavigation)
    return () => window.removeEventListener('category-navigation', blockNavigation)
  }, [completing])
  useEffect(() => {const labels: Record<string, string> = {'crews': t('navigation.crews'), 'start-lists': t('navigation.startLists'), 'disciplines': t('navigation.disciplines'), 'results': t('navigation.results'), 'result-grid': t('navigation.resultGrid'), 'parallel-sprint': t('navigation.parallelSprint'), 'edit': t('competition.edit')}; const current = /^\/workspace\/\d+\/([^?]+)/.exec(path)?.[1]; document.title = [current ? labels[current] : t('workspace.overview'), competition?.title, 'RaftScore'].filter(Boolean).join(' — ')}, [path, competition?.title, t])
  if (error || !id) return <Typography role="alert">{t('competition.error')}</Typography>
  if (!competition) return null
  if (competition.status === 'COMPLETED' && path.includes('/result-grid')) {window.location.replace(`#/workspace/${id}/results${path.includes('?') ? '?' + path.split('?')[1] : ''}`); return null}
  const section = /^\/workspace\/\d+\/([^?]+)/.exec(path)?.[1]
  const settingsParams = new URLSearchParams()
  const workspaceParams = new URLSearchParams(query)
  for (const key of ['discipline', 'category']) {const value = workspaceParams.get(key); if (value) settingsParams.set(key, value)}
  const disciplineEditParams = new URLSearchParams(workspaceParams)
  disciplineEditParams.set('editDisciplines', '1')
  const refreshCompetition = async () => {
    try {setCompetition(await apiRequest<Competition>(`/api/competitions/${id}`)); setCompetitionRefreshFailed(false)}
    catch (error) {setCompetitionRefreshFailed(true); throw error}
  }
  const body = section === 'edit' ? <Competitions readOnly={readOnly} workspaceEditId={id}/>
    : section === 'crews' ? <Crews fixedCompetition={id} readOnly={readOnly} canEdit={!readOnly && competition.status !== 'COMPLETED'} onReadinessChange={() => setCrewRevision(value => value + 1)}/>
    : section === 'disciplines' ? <Disciplines onDeletionStateChange={setRefreshingCompetition} competitionStatus={competition.status} onDeleted={refreshCompetition} compositionEditing={new URLSearchParams(path.split('?')[1] ?? '').get('editDisciplines') === '1'} fixedCompetition={id} scoringAllowed={competition.status === 'STARTED'} readOnly={readOnly || competition.status === 'COMPLETED'} onReadinessChange={() => setDisciplineRevision(value => value + 1)}/>
    : section === 'results' ? <Results fixedCompetition={id} readOnly={readOnly || competition.status !== 'STARTED'}/>
    : section === 'start-lists' ? <StartLists fixedCompetition={id} readOnly={readOnly || competition.status === 'COMPLETED'} onReadinessChange={() => setStartRevision(value => value + 1)}/>
    : section === 'result-grid' && !readOnly && competition.status === 'STARTED' ? <ResultGrid fixedCompetition={id}/>
    : section === 'parallel-sprint' ? <ParallelSprint fixedCompetition={id} readOnly={readOnly || competition.status !== 'STARTED'}/> : null
  const changeStatus = async (status: 'STARTED' | 'COMPLETED') => {
    setCompleting(true)
    setCompletionError(false)
    try {
      const updated = await csrfRequest<Competition>(`/api/competitions/${id}/status`, 'PUT', {status, version: competition.version})
      if (status === 'COMPLETED') {
        setCompletionDrafts(null)
        window.dispatchEvent(new Event('result-completed'))
      }
      intentionalNavigation.current = true
      setCompetition(updated)
      const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
      params.delete('complete')
      const currentQuery = params.size ? '?' + params : ''
      setNextRoute(`/workspace/${id}/${status === 'STARTED' ? 'disciplines' : 'results'}${currentQuery}`)
    } catch {
      setCompletionError(true)
    } finally {
      setCompleting(false)
    }
  }
  const openCompletionDialog = () => {
    const detail = {count: 0, busy: false}
    window.dispatchEvent(new CustomEvent('result-dirty-count', {detail}))
    if (!detail.busy) setCompletionDrafts(detail.count)
  }
  return <Box sx={{display: 'grid', gridTemplateColumns: 'minmax(0, 1fr)', minWidth: 0, gap: {xs: 1, md: 2}, mt: {xs: 1, md: 2}}}>
    {!section ? <><Box className="workspace-heading"><Box>{(competition.startDate || competition.location) && <Typography color="text.secondary" variant="body2">{[competition.startDate && formatDate(new Date(`${competition.startDate}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC'), competition.location].filter(Boolean).join(' · ')}</Typography>}<Typography component="h1" variant="h5">{competition.title}</Typography><Box component="span" className={`status-pill status-${competition.status.toLowerCase()}`}>{t(`competition.states.${competition.status}`)}</Box></Box><Button component="a" href="#/competitions">{t('competition.back')}</Button></Box>
    <Box className="readiness-panel">{readiness && <Typography>{t('workspace.readiness', readiness)}</Typography>}{crewReadiness && <Typography>{t('workspace.crewReadiness', crewReadiness)}</Typography>}{query.includes('discipline=') && query.includes('category=') && startReadiness !== null && <Typography>{t('workspace.startReadiness', {count: startReadiness})}</Typography>}</Box>
    <Box className="workspace-actions">{!readOnly && competition.status !== 'COMPLETED' && <Button variant="outlined" component="a" href={`#/workspace/${id}/edit${settingsParams.size ? '?' + settingsParams : ''}`}>{t('competition.edit')}</Button>}{!readOnly && competition.status === 'CREATED' && <Button variant="contained" disabled={completing || refreshingCompetition || competitionRefreshFailed} onClick={() => void changeStatus('STARTED')}>{t('competition.start')}</Button>}{!readOnly && competition.status === 'STARTED' && <Button variant="outlined" disabled={completing} onClick={openCompletionDialog}>{t('competition.complete')}</Button>}</Box></> : <Typography component="h1" className="workspace-compact-heading" variant="h6">{[competition.title, competition.startDate && formatDate(new Date(`${competition.startDate}T00:00:00Z`), supportedLocale(i18n.language) ?? 'en', 'UTC'), competition.location].filter(Boolean).join(' — ')}</Typography>}
    {(!section || ['crews', 'start-lists', 'disciplines', 'parallel-sprint', 'edit'].includes(section)) && <Typography component="h2" variant="h6">{t(!section ? 'workspace.overview' : section === 'crews' ? 'navigation.crews' : section === 'start-lists' ? 'navigation.startLists' : section === 'disciplines' ? 'navigation.disciplines' : section === 'parallel-sprint' ? 'navigation.parallelSprint' : 'competition.edit')}</Typography>}
    {section === 'disciplines' && !readOnly && competition.status !== 'COMPLETED' && <Box><Button variant="outlined" component="a" href={`#/workspace/${id}/disciplines?${disciplineEditParams}`}>{t('competition.edit')}</Button></Box>}
    {completionError && <Typography role="alert">{t('competition.error')}</Typography>}
    <div inert={completing} style={{minWidth: 0}}>
      <fieldset disabled={completing} style={{border: 0, margin: 0, padding: 0, minWidth: 0}}>{body}</fieldset>
    </div>
    <Dialog open={completionDrafts !== null} onClose={() => setCompletionDrafts(null)} aria-labelledby="completion-title">
      <DialogTitle id="completion-title">{t('competition.complete')}</DialogTitle>
      <DialogContent>
        {completionError && <Typography role="alert">{t('competition.error')}</Typography>}
        {completionDrafts ? t('competition.unsavedRows', {count: completionDrafts}) : t('competition.confirmComplete')}
      </DialogContent>
      <DialogActions>
        <Button disabled={completing} onClick={() => setCompletionDrafts(null)}>{t('competition.cancel')}</Button>
        <Button variant="contained" disabled={completing} onClick={() => void changeStatus('COMPLETED')}>
          {completionDrafts ? t('competition.discardAndComplete') : t('competition.complete')}
        </Button>
      </DialogActions>
    </Dialog>
  </Box>
}
