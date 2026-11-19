import {useEffect, useState} from 'react'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'

type GridAttempt = {crewId: number, crewName?: string, attemptNumber: number, version: number | null, durationCentiseconds: number | null, startInstant: string | null, finishInstant: string | null, penaltyCentiseconds: number, status: string, gates: {number: number, penaltySeconds: number}[]}
type Heat = {id: number, version: number, round: number, number: number, first: {id: number, name: string} | null, second: {id: number, name: string} | null, firstResolved: boolean, secondResolved: boolean, firstStatus: string | null, secondStatus: string | null, firstTimeCentiseconds: number | null, secondTimeCentiseconds: number | null, winnerSlot: number | null, winnerOverridden: boolean}
type Discipline = {id: number, name: string, type: string, gateCount?: number | null}
type Crew = {id: number, name: string}
export default function ProtocolCorrection({competition, category, disciplines, crews, onSaved}: {competition: string, category: string, disciplines: Discipline[], crews: Crew[], onSaved: () => void}) {
  const {i18n} = useTranslation()
  const ru = i18n.resolvedLanguage?.startsWith('ru')
  const text = (r: string, e: string) => ru ? r : e
  const [discipline, setDiscipline] = useState('')
  const [grid, setGrid] = useState<GridAttempt[]>([])
  const [bracket, setBracket] = useState<{id: number, heats: Heat[]} | null>(null)
  const [key, setKey] = useState('')
  const [value, setValue] = useState<GridAttempt | null>(null)
  const [heat, setHeat] = useState<Heat | null>(null)
  const [winner, setWinner] = useState('auto')
  const [confirmed, setConfirmed] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const parallel = disciplines.find(d => String(d.id) === discipline)?.type === 'PARALLEL_SPRINT'
  const base = `/api/competitions/${competition}/disciplines/${discipline}/categories/${category}`
  useEffect(() => {
    let active = true
    setGrid([]); setBracket(null); setKey(''); setValue(null); setHeat(null); setError('')
    if (discipline) {
      const load = parallel ? apiRequest<{id: number, heats: Heat[]}>(`${base}/bracket`).then(v => {if (active) setBracket(v)}) : Promise.all([
        apiRequest<{attempts: GridAttempt[]}>(`${base}/attempt-grid`),
        apiRequest<{code: string, disciplineId: number, categoryId: number, crewId: number, crew: string, attempt: number}[]>(`/api/competitions/${competition}/completion-issues`),
      ]).then(([v, issues]) => {if (active) {
        const selected = disciplines.find(d => String(d.id) === discipline)
        const missing = issues.filter(i => i.code === 'missingAttempt' && String(i.disciplineId) === discipline && String(i.categoryId) === category
          && !v.attempts.some(a => a.crewId === i.crewId && a.attemptNumber === i.attempt))
          .map(i => ({crewId: i.crewId, crewName: i.crew, attemptNumber: i.attempt, version: null, durationCentiseconds: null,
            startInstant: null, finishInstant: null, penaltyCentiseconds: 0, status: 'FINISHED',
            gates: Array.from({length: selected?.type === 'SLALOM' ? selected.gateCount ?? 0 : 0}, (_, n) => ({number: n + 1, penaltySeconds: 0}))}))
        setGrid([...v.attempts, ...missing])
      }})
      void load.catch(() => active && setError(text('Не удалось загрузить результаты', 'Could not load results')))
    }
    return () => {active = false}
  }, [competition, category, discipline])
  const save = async () => {
    if ((!value && !heat) || !reason.trim()) return
    setError('')
    try {
      if (heat && bracket) {
        const winnerCrewId = winner === 'auto' ? null : Number(winner)
        await csrfRequest(`${base}/brackets/${bracket.id}/heats/${heat.id}/correction`, 'PUT', {reason, result: {version: heat.version, first: {status: heat.firstStatus, timeCentiseconds: heat.firstTimeCentiseconds}, second: {status: heat.secondStatus, timeCentiseconds: heat.secondTimeCentiseconds}, winnerCrewId, confirmed: winnerCrewId == null ? false : confirmed}})
      } else if (value) {
        const {crewId, crewName: _crewName, attemptNumber, ...result} = value
        await csrfRequest(`${base}/crews/${crewId}/attempts/corrections/${attemptNumber}`, 'PUT', {reason, result})
      }
      setReason(''); setValue(null); setHeat(null); setKey(''); setDiscipline(''); onSaved()
    } catch (failure) {
      setError(failure instanceof ApiError && failure.code === 'protocol.seeding_change'
        ? text('Исправление меняет посев существующей сетки и отклонено. Изменение посева уже сыгранной сетки рассматривается вне системы.', 'The correction changes the seeding of an existing bracket and was rejected. Changes to the seeding of a bracket with recorded heats are handled outside the system.')
        : text('Исправление не сохранено. Проверьте значения и обновите данные. Изменение участников уже проведённых зависимых заездов запрещено.', 'Correction was not saved. Check the values and refresh. Changes to participants in already recorded dependent heats are blocked.'))
    }
  }
  const statuses = ['FINISHED', 'DNS', 'DNF', 'DSQ_ATTEMPT', 'DSQ_DISCIPLINE', 'DSQ_COMPETITION']
  return <details><summary>{text('Исправить результат', 'Correct a result')}</summary>
    <p>{text('Исправление, меняющее посев или участников уже проведённых зависимых заездов, запрещено.', 'A correction cannot change seeding or participants in already recorded dependent heats.')}</p>
    <label>{text('Дисциплина', 'Discipline')} <select value={discipline} onChange={e => setDiscipline(e.target.value)}><option value="">—</option>{disciplines.map(d => <option key={d.id} value={d.id}>{d.name || d.type}</option>)}</select></label>
    {parallel ? <label>{text('Заезд', 'Heat')} <select value={key} onChange={e => {setKey(e.target.value); const selected = bracket?.heats.find(h => String(h.id) === e.target.value) ?? null; setHeat(selected && {...selected, firstStatus: selected.firstStatus ?? 'FINISHED', secondStatus: selected.secondStatus ?? 'FINISHED'}); setWinner(selected?.winnerOverridden ? String(selected.winnerSlot === 1 ? selected.first?.id : selected.second?.id) : 'auto'); setConfirmed(false)}}><option value="">—</option>{bracket?.heats.filter(h => h.first && h.second && h.firstResolved && h.secondResolved).map(h => <option key={h.id} value={h.id}>{h.round} / {h.number}: {h.first?.name} — {h.second?.name}</option>)}</select></label> : <label>{text('Попытка', 'Attempt')} <select value={key} onChange={e => {setKey(e.target.value); setValue(grid.find(a => `${a.crewId}:${a.attemptNumber}` === e.target.value) ?? null)}}><option value="">—</option>{grid.map(a => <option key={`${a.crewId}:${a.attemptNumber}`} value={`${a.crewId}:${a.attemptNumber}`}>{a.crewName ?? crews.find(c => c.id === a.crewId)?.name ?? a.crewId} — {a.attemptNumber}</option>)}</select></label>}
    {value && <div><label>{text('Время, сотые', 'Time, centiseconds')} <input type="number" min="0" value={value.durationCentiseconds ?? ''} onChange={e => setValue({...value, durationCentiseconds: e.target.value === '' ? null : Number(e.target.value), startInstant: null, finishInstant: null})}/></label>
      <label>{text('Штраф, сотые', 'Penalty, centiseconds')} <input type="number" min="0" value={value.penaltyCentiseconds} onChange={e => setValue({...value, penaltyCentiseconds: Number(e.target.value)})}/></label>
      <label>{text('Статус', 'Status')} <select value={value.status} onChange={e => setValue({...value, status: e.target.value})}>{statuses.map(s => <option key={s}>{s}</option>)}</select></label>
      {value.gates.map((g, index) => <label key={g.number}>{text('Ворота', 'Gate')} {g.number} <input type="number" min="0" value={g.penaltySeconds} onChange={e => setValue({...value, gates: value.gates.map((gate, i) => i === index ? {...gate, penaltySeconds: Number(e.target.value)} : gate)})}/></label>)}
    </div>}
    {heat && <div>{([1, 2] as const).map(slot => {const first = slot === 1, name = first ? heat.first?.name : heat.second?.name, status = first ? heat.firstStatus : heat.secondStatus, time = first ? heat.firstTimeCentiseconds : heat.secondTimeCentiseconds; return <div key={slot}><strong>{name}</strong> <label>{text('Статус', 'Status')} <select value={status ?? 'FINISHED'} onChange={e => setHeat({...heat, ...(first ? {firstStatus: e.target.value} : {secondStatus: e.target.value})})}>{statuses.map(s => <option key={s}>{s}</option>)}</select></label> <label>{text('Время, сотые', 'Time, centiseconds')} <input type="number" min="0" value={time ?? ''} onChange={e => setHeat({...heat, ...(first ? {firstTimeCentiseconds: e.target.value === '' ? null : Number(e.target.value)} : {secondTimeCentiseconds: e.target.value === '' ? null : Number(e.target.value)})})}/></label></div>})}<label>{text('Решение о победителе', 'Winner decision')} <select value={winner} onChange={e => {setWinner(e.target.value); setConfirmed(false)}}><option value="auto">{text('Определить по результату', 'Determine from results')}</option><option value={heat.first?.id}>{heat.first?.name}</option><option value={heat.second?.id}>{heat.second?.name}</option></select></label>{winner !== 'auto' && <label><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)}/>{text('Подтверждаю решение судьи', 'Confirm judge decision')}</label>}</div>}
    {(value || heat) && <div><label>{text('Причина', 'Reason')} <input value={reason} maxLength={2000} onChange={e => setReason(e.target.value)}/></label><button disabled={!reason.trim() || (!!heat && winner !== 'auto' && !confirmed)} onClick={() => void save()}>{text('Сохранить исправление', 'Save correction')}</button></div>}
    {error && <p role="alert">{error}</p>}
  </details>
}
