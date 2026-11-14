import {useEffect, useState} from 'react'
import {Box, Button, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import StartLists from './StartLists'
import Results from './Results'
import ResultGrid from './ResultGrid'
import ParallelSprint from './ParallelSprint'
import {apiRequest, csrfRequest} from './api/client'

type Competition = {id: number, title: string, version: number, status: 'CREATED' | 'STARTED' | 'COMPLETED'}
export default function CompetitionWorkspace({path, readOnly}: {path: string, readOnly: boolean}) {
  const {t} = useTranslation()
  const id = /^\/workspace\/(\d+)(?:\/(results|result-grid|parallel-sprint|start-lists))?(?:\?.*)?$/.exec(path)?.[1]
  const [competition, setCompetition] = useState<Competition | null>(null)
  const [error, setError] = useState(false)
  const [completionError, setCompletionError] = useState(false)
  const [completing, setCompleting] = useState(false)
  useEffect(() => {let active = true; if (!id) {setError(true); return} apiRequest<Competition>(`/api/competitions/${id}`).then(c => {if (active) setCompetition(c)}).catch(() => {if (active) setError(true)}); return () => {active = false}}, [id])
  useEffect(() => {
    const blockNavigation = (event: Event) => {if (completing) event.preventDefault()}
    window.addEventListener('category-navigation', blockNavigation)
    return () => window.removeEventListener('category-navigation', blockNavigation)
  }, [completing])
  if (error) return <Typography role="alert">{t('competition.error')}</Typography>
  if (!competition) return null
  if (competition.status === 'CREATED') return <Box sx={{mt: 2}}><Typography>{competition.title} · {t('competition.states.CREATED')}</Typography><Button component="a" href="#/competitions">{t('competition.back')}</Button></Box>
  const section = /^\/workspace\/\d+\/([^?]+)/.exec(path)?.[1]
  const body = section === 'results' ? <Results fixedCompetition={id} readOnly={readOnly || competition.status !== 'STARTED'}/>
    : section === 'start-lists' ? <StartLists fixedCompetition={id} readOnly={readOnly || competition.status !== 'STARTED'}/>
    : section === 'result-grid' && !readOnly && competition.status === 'STARTED' ? <ResultGrid fixedCompetition={id}/>
    : section === 'parallel-sprint' ? <ParallelSprint fixedCompetition={id} readOnly={readOnly || competition.status !== 'STARTED'}/> : null
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}><Typography variant="h5">{competition.title}</Typography><Typography>{t(`competition.states.${competition.status}`)}</Typography>
    <Box sx={{pointerEvents: completing ? 'none' : undefined}}><Button component="a" href={`#/workspace/${id}/start-lists`}>{t('navigation.startLists')}</Button><Button component="a" href={`#/workspace/${id}/results`}>{t('navigation.results')}</Button>
      {competition.status === 'STARTED' && !readOnly && <><Button component="a" href={`#/workspace/${id}/result-grid`}>{t('navigation.resultGrid')}</Button></>}<Button component="a" href={`#/workspace/${id}/parallel-sprint`}>{t('navigation.parallelSprint')}</Button>
      {!readOnly && competition.status === 'STARTED' && <Button disabled={completing} onClick={async () => {if (!window.confirm(t('competition.confirmComplete'))) return; if (!window.dispatchEvent(new Event('category-navigation', {cancelable: true}))) return; setCompleting(true); setCompletionError(false); try {setCompetition(await csrfRequest<Competition>(`/api/competitions/${id}/status`, 'PUT', {status: 'COMPLETED', version: competition.version}))} catch {setCompletionError(true)} finally {setCompleting(false)}}}>{t('competition.complete')}</Button>}<Button component="a" href="#/competitions">{t('competition.back')}</Button></Box>{completionError && <Typography role="alert">{t('competition.error')}</Typography>}<div inert={completing}><fieldset disabled={completing} style={{border: 0, margin: 0, padding: 0, minWidth: 0}}>{body}</fieldset></div></Box>
}
