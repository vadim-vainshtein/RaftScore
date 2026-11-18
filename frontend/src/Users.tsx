import {useEffect, useState} from 'react'
import {useTranslation} from 'react-i18next'
import {Alert, Box, Button, TextField, Typography} from '@mui/material'
import {apiRequest, csrfRequest} from './api/client'
import {apiErrorKey, type ApiErrorKey} from './api/errors'

type User = {id: number, username: string, role: 'FULL_ACCESS' | 'READ_ONLY', enabled: boolean}
export default function Users() {
  const {t} = useTranslation()
  const {t: auth} = useTranslation('auth')
  const [users, setUsers] = useState<User[]>([])
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [reset, setReset] = useState<Record<number, string>>({})
  const [error, setError] = useState<ApiErrorKey | null>(null)
  const [busy, setBusy] = useState(false)
  const load = async () => {
    const value = await apiRequest<User[]>('/api/users')
    if (!Array.isArray(value)) throw new Error('Invalid user list')
    setUsers(value)
  }
  useEffect(() => {void load().catch(e => setError(apiErrorKey(e)))}, [])
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(null)
    try {await action(); await load()} catch (e) {setError(apiErrorKey(e))} finally {setBusy(false)}
  }
  const create = () => run(async () => {
    await csrfRequest('/api/users', 'POST', {username, password})
    setUsername(''); setPassword('')
  })
  const resetPassword = (id: number) => run(async () => {
    await csrfRequest(`/api/users/${id}/password`, 'PUT', {password: reset[id]})
    setReset(previous => ({...previous, [id]: ''}))
  })
  return <Box sx={{display: 'grid', gap: 2, mt: 2}}>
    {error && <Alert severity="error">{t(error)}</Alert>}
    <Box component="form" onSubmit={event => {event.preventDefault(); void create()}} sx={{display: 'flex', flexWrap: 'wrap', gap: 1}}>
      <TextField label={t('users.username')} value={username} onChange={e => setUsername(e.target.value)} required/>
      <TextField label={t('users.initialPassword')} type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} required/>
      <Button type="submit" disabled={busy}>{t('users.create')}</Button>
    </Box>
    {users.map(user => <Box key={user.id} component="section" aria-label={user.username} sx={{display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1}}>
      <Typography>{user.username} — {auth(`roles.${user.role}`)} — {t(user.enabled ? 'users.enabled' : 'users.blocked')}</Typography>
      {user.role === 'READ_ONLY' && user.enabled && <>
        <TextField label={t('users.newPassword')} type="password" autoComplete="new-password" value={reset[user.id] ?? ''} onChange={e => setReset({...reset, [user.id]: e.target.value})}/>
        <Button disabled={busy || !reset[user.id]} onClick={() => void resetPassword(user.id)}>{t('users.reset')}</Button>
        <Button disabled={busy} onClick={() => void run(() => csrfRequest(`/api/users/${user.id}/block`, 'PATCH'))}>{t('users.block')}</Button>
      </>}
    </Box>)}
  </Box>
}
