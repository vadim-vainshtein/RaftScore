import {useEffect, useState} from 'react'
import {Box, Button, Checkbox, FormControlLabel, TextField, Typography} from '@mui/material'
import {useTranslation} from 'react-i18next'
import {apiRequest, ApiError, csrfRequest} from './api/client'

type Seat = {id: number, name: string, deleted: boolean, version: number}
type ErrorKey = 'error' | 'seatDuplicate'

export default function SeatNames({onChanged}: {onChanged?: () => void}) {
  const {t} = useTranslation('categories')
  const [items, setItems] = useState<Seat[]>([])
  const [error, setError] = useState<ErrorKey | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    apiRequest<Seat[]>('/api/seat-names?includeDeleted=true').then(setItems).catch(() => setError('error'))
  }, [])
  const update = (item: Seat) => setItems(items.map(value => value.id === item.id ? item : value))
  const save = async (item: Seat) => {
    setBusy(true); setError(null)
    try {
      const saved = await csrfRequest<Seat>(`/api/seat-names/${item.id}`, 'PUT', {
        name: item.name, deleted: item.deleted, version: item.version,
      })
      update(saved); onChanged?.()
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 && (e.body as {code?: string})?.code === 'seat_name.duplicate' ? 'seatDuplicate' : 'error')
    } finally { setBusy(false) }
  }
  return <Box sx={{mt: 2}}>
    <Typography variant="h5">{t('seatNames')}</Typography>
    {error && <Typography role="alert">{t(error)}</Typography>}
    {items.map(item => <Box key={item.id} sx={{display: 'flex', gap: 1, my: 1}}>
      <TextField label={t('name')} value={item.name} disabled={busy} slotProps={{htmlInput: {maxLength: 200}}}
        onChange={e => update({...item, name: e.target.value})}/>
      <FormControlLabel label={t('deleted')} control={<Checkbox checked={item.deleted} disabled={busy}
        onChange={e => update({...item, deleted: e.target.checked})}/>}/>
      <Button disabled={busy || !item.name.trim()} onClick={() => void save(item)}>{t('save')}</Button>
    </Box>)}
  </Box>
}
