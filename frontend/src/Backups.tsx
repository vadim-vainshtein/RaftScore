import {useCallback, useEffect, useRef, useState} from 'react'
import {Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'
import {apiErrorKey, type ApiErrorKey} from './api/errors'
import {formatDate, formatNumber} from './i18n/format'
import {supportedLocale} from './i18n/supported-locales'

type Backup = {id: string, createdAt: string, sizeBytes: number, formatVersion: string, schemaVersion: string}
type Source = {kind: 'stored', backup: Backup} | {kind: 'upload', file: File}
type BackupError = ApiErrorKey | 'backup.reauthenticationFailed' | 'backup.restoreBusy' | 'backup.restoreFailed' | 'backup.confirmationRequired'

function errorKey(error: unknown): BackupError {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'backup.reauthentication_failed': return 'backup.reauthenticationFailed'
      case 'backup.restore_busy': return 'backup.restoreBusy'
      case 'backup.restore_failed': return 'backup.restoreFailed'
      case 'backup.confirmation_required': return 'backup.confirmationRequired'
    }
  }
  return apiErrorKey(error)
}

export default function Backups() {
  const {t, i18n} = useTranslation()
  const locale = supportedLocale(i18n.resolvedLanguage) ?? 'en'
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  const uploadInput = useRef<HTMLInputElement>(null)
  const [backups, setBackups] = useState<Backup[]>([])
  const [source, setSource] = useState<Source | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<BackupError | null>(null)
  const [loading, setLoading] = useState(true)
  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {setBackups(await apiRequest<Backup[]>('/api/backups'))} catch (e) {setError(errorKey(e))} finally {setLoading(false)}
  }, [])
  useEffect(() => {
    let active = true
    apiRequest<Backup[]>('/api/backups').then(value => {if (active) setBackups(value)}).catch(e => {if (active) setError(errorKey(e))}).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [])
  const create = async () => {
    setBusy(true); setError(null)
    try {await csrfRequest('/api/backups', 'POST'); await load()} catch (e) {setError(errorKey(e))} finally {setBusy(false)}
  }
  const choose = (value: Source) => {setSource(value); setConfirmed(false); setPassword(''); setError(null)}
  const cancel = () => {setPassword(''); setConfirmed(false); setSource(null); setError(null)}
  const restore = async () => {
    if (!source || !confirmed || !password || busy) return
    const currentPassword = password
    setPassword(''); setBusy(true); setError(null)
    try {
      if (source.kind === 'stored') {
        await csrfRequest('/api/backups/restore', 'POST', {id: source.backup.id, confirmed: true, password: currentPassword})
      } else {
        const csrf = await apiRequest<{headerName: string, token: string}>('/api/auth/csrf')
        const body = new FormData()
        body.append('file', source.file); body.append('confirmed', 'true'); body.append('password', currentPassword)
        await apiRequest('/api/backups/restore', {method: 'POST', headers: {[csrf.headerName]: csrf.token}, body})
      }
      setSource(null); setConfirmed(false)
      window.dispatchEvent(new Event('session-check'))
    } catch (e) {setError(errorKey(e))} finally {setBusy(false)}
  }
  const date = (value: string | number) => {
    const parsed = new Date(value)
    return Number.isNaN(parsed.getTime()) ? t('backup.unavailable') : formatDate(parsed, locale, zone, {dateStyle: 'medium', timeStyle: 'short'})
  }
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    <Box><Button onClick={() => void create()} disabled={busy || loading}>{t('backup.create')}</Button><Button onClick={() => void load()} disabled={busy || loading}>{t('backup.refresh')}</Button></Box>
    {error && !source && <Typography role="alert">{t(error)}</Typography>}
    {loading ? <Typography>{t('backup.loading')}</Typography> : backups.length === 0 ? <Typography>{t('backup.empty')}</Typography> : <Box sx={{overflowX: 'auto'}}><Table><TableHead><TableRow>{(['created','format','schema','size','actions'] as const).map(key => <TableCell key={key}>{t(`backup.${key}`)}</TableCell>)}</TableRow></TableHead><TableBody>{backups.map(backup => <TableRow key={backup.id}>
      <TableCell>{date(backup.createdAt)}</TableCell><TableCell>{backup.formatVersion}</TableCell><TableCell>{backup.schemaVersion}</TableCell><TableCell>{t('backup.bytes', {count: backup.sizeBytes, size: formatNumber(backup.sizeBytes, locale)})}</TableCell>
      <TableCell><Button component="a" href={`/api/backups/${encodeURIComponent(backup.id)}/download`} download aria-label={t('backup.downloadNamed', {date: date(backup.createdAt), id: backup.id})} disabled={busy || loading}>{t('backup.download')}</Button><Button disabled={busy || loading} aria-label={t('backup.restoreNamed', {date: date(backup.createdAt), id: backup.id})} onClick={() => choose({kind: 'stored', backup})}>{t('backup.restore')}</Button></TableCell>
    </TableRow>)}</TableBody></Table></Box>}
    <Button disabled={busy || loading} onClick={() => uploadInput.current?.click()}>{t('backup.chooseUpload')}</Button><input ref={uploadInput} type="file" accept=".zip,application/zip" hidden onChange={event => {const file = event.target.files?.[0]; if (file) choose({kind: 'upload', file}); event.target.value = ''}}/>
    <Dialog open={!!source} onClose={busy ? undefined : cancel} aria-labelledby="restore-title" fullWidth>
      <DialogTitle id="restore-title">{t('backup.confirmTitle')}</DialogTitle><DialogContent sx={{display: 'grid', gap: 2}}>
        <Typography>{t('backup.warning')}</Typography>
        {source?.kind === 'stored' && <Typography>{t('backup.storedSource', {date: date(source.backup.createdAt)})}</Typography>}
        {source?.kind === 'upload' && <><Typography>{t('backup.uploadSource', {name: source.file.name})}</Typography><Typography>{t('backup.modified', {date: source.file.lastModified ? date(source.file.lastModified) : t('backup.unavailable')})}</Typography></>}
        <FormControlLabel control={<Checkbox checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={busy}/>} label={t('backup.confirm')}/>
        <TextField label={t('backup.password')} type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} disabled={busy}/>
        {error && <Typography role="alert">{t(error)}</Typography>}
      </DialogContent><DialogActions><Button onClick={cancel} disabled={busy}>{t('backup.cancel')}</Button><Button onClick={() => void restore()} disabled={busy || !confirmed || !password}>{t('backup.confirmRestore')}</Button></DialogActions>
    </Dialog>
  </Box>
}
