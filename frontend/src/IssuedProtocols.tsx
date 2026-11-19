import ProtocolCorrection from './ProtocolCorrection'
import {useEffect, useState} from 'react'
import {useTranslation} from 'react-i18next'
import {apiRequest, csrfRequest} from './api/client'

type Attempt = {number: number, durationCentiseconds: number | null, penaltyCentiseconds: number, status: string, gates: {number: number, penaltySeconds: number}[]}
type Result = {crewId: number, place: number | null, totalCentiseconds: number | null, status: string, points: number, attempts: Attempt[]}
type Heat = {round: number, number: number, crew1: number | null, crew2: number | null, time1Centiseconds: number | null, time2Centiseconds: number | null, status1: string | null, status2: string | null, winnerSlot: number | null, winnerOverridden: boolean}
type Category = {id: number, name: string, crews: {id: number, name: string, region: string, number: number, members: string[]}[], disciplines: {id: number, name: string, type: string, results: Result[], heats: Heat[]}[], standings: {crewId: number, place: number | null, totalPoints: number}[]}
type CorrectionValue = {disciplineId: number, categoryId: number, crewId: number, attemptNumber: number, durationCentiseconds: number | null, startInstant: string | null, finishInstant: string | null, status: string, penaltyCentiseconds: number, gates: {number: number, penaltySeconds: number}[]}
type HeatChange = {disciplineId: number, categoryId: number, round: number, heat: number, crew1: number, crew2: number, status1: string | null, status2: string | null, time1Centiseconds: number | null, time2Centiseconds: number | null, winnerSlot: number | null}
type Change = {kind?: 'heat', before: HeatChange, after: HeatChange} | {kind?: undefined, before: CorrectionValue, after: CorrectionValue}
type Edition = {edition: number, issuedAt: string, stale: boolean, changes: {id: number, correctedAt: string, reason: string, detail: Change}[], snapshot: {competition: string, location: string, timeZone: string, categories: Category[]}}
const formatTime = (value: number | null) => value == null ? '—' : `${String(Math.floor(value / 6000)).padStart(2, '0')}:${String(Math.floor(value / 100) % 60).padStart(2, '0')}.${String(value % 100).padStart(2, '0')}`

export default function IssuedProtocols({competition, category, readOnly, disciplines, crews}: {competition: string, category: string, readOnly: boolean, disciplines: {id: number, name: string, type: string, gateCount?: number | null}[], crews: {id: number, name: string}[]}) {
  const {i18n} = useTranslation()
  const ru = i18n.resolvedLanguage?.startsWith('ru')
  const label = (r: string, e: string) => ru ? r : e
  const status = (value: string | null) => ({MISSING: label('Нет результата', 'Missing result'), FINISHED: label('Финиш', 'Finished'), DNS: label('Не стартовал', 'Did not start'), DNF: label('Не финишировал', 'Did not finish'), DSQ_ATTEMPT: label('Дисквалификация попытки', 'Attempt disqualified'), DSQ_DISCIPLINE: label('Дисквалификация в дисциплине', 'Disqualified in discipline'), DSQ_COMPETITION: label('Дисквалификация в соревновании', 'Disqualified in competition')} as Record<string, string>)[value ?? 'MISSING'] ?? value ?? '—'
  const [editions, setEditions] = useState<Edition[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const base = `/api/competitions/${competition}/protocols`
  const load = async () => {const data = await apiRequest<Edition[]>(base); setEditions(data); setSelected(old => old && data.some(e => e.edition === old) ? old : data[0]?.edition ?? null)}
  useEffect(() => {setEditions([]); setSelected(null); if (!competition) return; const refresh = () => void load().catch(() => setError(label('Не удалось загрузить протоколы', 'Could not load protocols'))); refresh(); const timer = window.setInterval(refresh, 2000); return () => window.clearInterval(timer)}, [competition])
  const current = editions.find(e => e.edition === selected)
  const cat = current?.snapshot.categories.find(c => String(c.id) === category)
  useEffect(() => {
    let original: HTMLElement | null = null
    let clone: HTMLElement | null = null
    const before = () => {
      original = document.getElementById('issued-protocol')
      if (!original) return
      clone = original.cloneNode(true) as HTMLElement
      original.id = 'protocol-preview'
      document.body.appendChild(clone)
    }
    const after = () => {clone?.remove(); if (original) original.id = 'issued-protocol'; clone = null; original = null}
    window.addEventListener('beforeprint', before)
    window.addEventListener('afterprint', after)
    return () => {window.removeEventListener('beforeprint', before); window.removeEventListener('afterprint', after); after()}
  }, [])
  const issue = async () => {setBusy(true); setError(''); try {const e = await csrfRequest<Edition>(base, 'POST'); await load(); setSelected(e.edition)} catch {setError(label('Выдача невозможна: проверьте завершение и полноту результатов', 'Cannot issue: check completion and results'))} finally {setBusy(false)}}
  return <section>
    <h3>{label('Выданные протоколы', 'Issued protocols')}</h3>
    {!editions.some(e => !e.stale) && <p>{label('Актуального протокола нет; текущие результаты предварительные.', 'There is no current protocol; live results are preliminary.')}</p>}
    {!readOnly && <button disabled={busy} onClick={() => void issue()}>{label('Выдать протокол', 'Issue protocol')}</button>}
    {editions.length > 0 && <label>{label('Редакция', 'Edition')} <select value={selected ?? ''} onChange={e => setSelected(Number(e.target.value))}>{editions.map(e => <option key={e.edition} value={e.edition}>{e.edition} — {e.stale ? label('устарел', 'stale') : label('актуальный', 'current')}</option>)}</select></label>}
    {error && <p role="alert">{error}</p>}
    {!readOnly && <ProtocolCorrection competition={competition} category={category} disciplines={disciplines} crews={cat?.crews ?? crews} onSaved={() => {void load()}}/>}
    {current && cat && <><button onClick={() => window.print()}>{label('Печать', 'Print')}</button><article id="issued-protocol">
      <style>{`@media print { @page { margin: 12mm } html, body { height: auto !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important } body > :not(#issued-protocol) { display: none !important } #issued-protocol { color: black; background: white; font-size: 12px; margin: 0; padding: 0 } #issued-protocol h1 { font-size: 20px } #issued-protocol h2 { font-size: 17px } #issued-protocol h3 { font-size: 14px; break-after: avoid } #issued-protocol p { margin: 6px 0 } #issued-protocol table { page-break-inside: auto } #issued-protocol tr { page-break-inside: avoid } } #issued-protocol table { border-collapse: collapse; width: 100% } #issued-protocol th, #issued-protocol td { border: 1px solid; padding: 3px; text-align: left }`}</style>
      <h1>{current.snapshot.competition}</h1><h2>{cat.name}</h2>
      <p>{label('Редакция', 'Edition')} {current.edition} · {current.stale ? label('УСТАРЕЛ', 'STALE') : label('Актуальный', 'Current')} · {label('Выдан', 'Issued')} {new Date(current.issuedAt).toLocaleString(ru ? 'ru-RU' : 'en-US', {timeZone: current.snapshot.timeZone})} ({current.snapshot.timeZone})</p>
      {current.changes.filter(c => c.detail.before.categoryId === cat.id).map(c => {
        const detail = c.detail
        const disciplineName = cat.disciplines.find(x => x.id === detail.before.disciplineId)
        if (detail.kind === 'heat') {
          const before = detail.before, after = detail.after
          const crew = (id: number) => cat.crews.find(x => x.id === id)?.name ?? String(id)
          return <p key={c.id}>{label('Исправление', 'Correction')}: {disciplineName?.name || disciplineName?.type}, {label('раунд', 'round')} {before.round}, {label('заезд', 'heat')} {before.heat}. {crew(before.crew1)}: {status(before.status1)} {formatTime(before.time1Centiseconds)} → {status(after.status1)} {formatTime(after.time1Centiseconds)}; {crew(before.crew2)}: {status(before.status2)} {formatTime(before.time2Centiseconds)} → {status(after.status2)} {formatTime(after.time2Centiseconds)}. {label('Победитель', 'Winner')}: {before.winnerSlot ? crew(before.winnerSlot === 1 ? before.crew1 : before.crew2) : '—'} → {after.winnerSlot ? crew(after.winnerSlot === 1 ? after.crew1 : after.crew2) : '—'}. {label('Причина', 'Reason')}: {c.reason}</p>
        }
        const before = detail.before as CorrectionValue, after = detail.after as CorrectionValue
        const gateNumbers = [...new Set([...before.gates.map(g => g.number), ...(after.gates ?? []).map(g => g.number)])].sort((a, b) => a - b)
        return <p key={c.id}>{label('Исправление', 'Correction')}: {cat.crews.find(x => x.id === before.crewId)?.name}, {disciplineName?.name || disciplineName?.type}, {label('попытка', 'attempt')} {before.attemptNumber}. {label('Время', 'Time')}: {formatTime(before.durationCentiseconds)} → {formatTime(after.durationCentiseconds)}. {label('Статус', 'Status')}: {status(before.status)} → {status(after.status)}. {(before.startInstant !== after.startInstant || before.finishInstant !== after.finishInstant) && <>{label('Старт / финиш', 'Start / finish')}: {before.startInstant ?? '—'} / {before.finishInstant ?? '—'} → {after.startInstant ?? '—'} / {after.finishInstant ?? '—'}. </>} {label('Штраф', 'Penalty')}: {formatTime(before.penaltyCentiseconds)} → {formatTime(after.penaltyCentiseconds)}. {gateNumbers.map(n => `${label('Ворота', 'Gate')} ${n}: ${before.gates.find(x => x.number === n)?.penaltySeconds ?? 0} → ${after.gates?.find(x => x.number === n)?.penaltySeconds ?? 0} ${label('с', 's')}`).join('; ')}. {label('Причина', 'Reason')}: {c.reason}</p>
      })}
      <h3>{label('Общий зачёт и состав', 'Overall standings and roster')}</h3>
      <table><thead><tr><th>{label('Место', 'Place')}</th><th>{label('Экипаж и состав', 'Crew and roster')}</th><th>{label('Очки', 'Points')}</th></tr></thead><tbody>{cat.standings.map(s => {const crew = cat.crews.find(c => c.id === s.crewId); return <tr key={s.crewId}><td>{s.place ?? '—'}</td><td>{crew?.number} {crew?.name} ({crew?.region})<br/>{crew?.members.join(', ')}</td><td>{s.totalPoints}</td></tr>})}</tbody></table>
      {cat.disciplines.map(d => <div key={d.id}><h3>{d.name || d.type}</h3><table><thead><tr><th>{label('Место', 'Place')}</th><th>{label('Экипаж', 'Crew')}</th><th>{label('Статус', 'Status')}</th><th>{label('Время', 'Time')}</th><th>{label('Очки', 'Points')}</th><th>{label('Попытки и штрафы', 'Attempts and penalties')}</th></tr></thead><tbody>{d.results.map(r => <tr key={r.crewId}><td>{r.place ?? '—'}</td><td>{cat.crews.find(c => c.id === r.crewId)?.name}</td><td>{status(r.status)}</td><td>{formatTime(r.totalCentiseconds)}</td><td>{r.points}</td><td>{r.attempts.map(a => `${a.number}: ${status(a.status)}, ${formatTime(a.durationCentiseconds)}, +${formatTime(a.penaltyCentiseconds)}; ${a.gates.map(g => `${g.number}: +${g.penaltySeconds} ${label('с', 's')}`).join(', ')}`).join(' / ')}</td></tr>)}</tbody></table></div>)}
      {cat.disciplines.filter(d => d.heats.length > 0).map(d => <div key={d.id}><h3>{d.name || d.type} — {label('заезды', 'heats')}</h3><table><thead><tr><th>{label('Раунд / заезд', 'Round / heat')}</th><th>{label('Экипаж', 'Crew')}</th><th>{label('Результат', 'Result')}</th><th>{label('Экипаж', 'Crew')}</th><th>{label('Результат', 'Result')}</th><th>{label('Победитель', 'Winner')}</th></tr></thead><tbody>{d.heats.map(h => <tr key={`${h.round}:${h.number}`}><td>{h.round} / {h.number}</td><td>{cat.crews.find(c => c.id === h.crew1)?.name ?? '—'}</td><td>{h.status1 ? `${status(h.status1)} ${formatTime(h.time1Centiseconds)}` : '—'}</td><td>{cat.crews.find(c => c.id === h.crew2)?.name ?? '—'}</td><td>{h.status2 ? `${status(h.status2)} ${formatTime(h.time2Centiseconds)}` : '—'}</td><td>{cat.crews.find(c => c.id === (h.winnerSlot === 1 ? h.crew1 : h.winnerSlot === 2 ? h.crew2 : null))?.name ?? '—'}{h.winnerOverridden && ` (${label('решение судьи', 'judge decision')})`}</td></tr>)}</tbody></table></div>)}
      <p>{label('Подпись', 'Signature')}: ____________________________</p>
    </article></>}
  </section>
}
