import {useState} from 'react'
import {useTranslation} from 'react-i18next'
import {Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography} from '@mui/material'
import {ApiError, csrfRequest} from './api/client'
import {apiErrorKey, type ApiErrorKey} from './api/errors'

export default function ChangePassword() {
  const {t} = useTranslation()
  const [open, setOpen] = useState(false)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<ApiErrorKey | 'users.currentPasswordInvalid' | null>(null)
  const [busy, setBusy] = useState(false)
  const close = () => {setOpen(false); setCurrent(''); setNext(''); setError(null)}
  const save = async () => {
    setBusy(true); setError(null)
    try {
      await csrfRequest('/api/auth/password', 'PUT', {currentPassword: current, newPassword: next})
      close(); window.dispatchEvent(new Event('session-check'))
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'auth.current_password_invalid' ? 'users.currentPasswordInvalid' : apiErrorKey(e))
    } finally {setBusy(false)}
  }
  return <><Button color="inherit" onClick={() => setOpen(true)}>{t('users.changeOwn')}</Button>
    <Dialog open={open} onClose={busy ? undefined : close} fullWidth>
      <DialogTitle>{t('users.changeOwn')}</DialogTitle>
      <DialogContent sx={{display: 'grid', gap: 2, pt: '12px !important'}}>
        <TextField label={t('users.currentPassword')} type="password" autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)}/>
        <TextField label={t('users.newPassword')} type="password" autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)}/>
        {error && <Typography role="alert">{t(error)}</Typography>}
      </DialogContent>
      <DialogActions><Button onClick={close} disabled={busy}>{t('users.cancel')}</Button><Button onClick={() => void save()} disabled={busy || !current || !next}>{t('users.save')}</Button></DialogActions>
    </Dialog>
  </>
}
