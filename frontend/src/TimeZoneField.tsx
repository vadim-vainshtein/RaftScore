import {useMemo} from 'react'
import {Autocomplete, TextField} from '@mui/material'
import {useTranslation} from 'react-i18next'

export default function TimeZoneField({id, value, date, onChange}: {id?: string, value: string, date: string, onChange: (value: string) => void}) {
  const {t} = useTranslation()
  const zones = useMemo(() => {
    const reference = date ? new Date(date + 'T12:00:00Z') : new Date()
    const at = Number.isNaN(reference.getTime()) ? new Date() : reference
    return [...new Set(['UTC', Intl.DateTimeFormat().resolvedOptions().timeZone, ...Intl.supportedValuesOf('timeZone'), value])].filter(Boolean).map(zone => {
      try {
        const offset = new Intl.DateTimeFormat('en', {timeZone: zone, timeZoneName: 'shortOffset'}).formatToParts(at).find(part => part.type === 'timeZoneName')?.value.replace('GMT', 'UTC') ?? 'UTC'
        return {zone, label: zone + ' ' + offset}
      } catch { return {zone, label: zone} }
    })
  }, [date, value])
  return <Autocomplete id={id} disableClearable options={zones} value={zones.find(option => option.zone === value) ?? zones[0]} isOptionEqualToValue={(a, b) => a.zone === b.zone} getOptionLabel={option => option.label} onChange={(_, option) => onChange(option.zone)} renderInput={params => <TextField {...params} label={t('competition.timeZone')}/>} />
}
