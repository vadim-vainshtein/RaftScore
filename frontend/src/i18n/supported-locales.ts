import {enUS, ruRU} from '@mui/material/locale'
import en from './locales/en/common.json'
import ru from './locales/ru/common.json'

export const locales = {
  en: {autonym: 'English', mui: enUS, common: en},
  ru: {autonym: 'Русский', mui: ruRU, common: ru},
} as const
export type Locale = keyof typeof locales
export const localeStorageKey = 'raftscore.language'

export function supportedLocale(value: string | null | undefined): Locale | undefined {
  if (!value) return undefined
  try {
    const canonical = new Intl.Locale(value)
    const entries = Object.keys(locales) as Locale[]
    return entries.find(locale => locale.toLowerCase() === canonical.toString().toLowerCase())
      ?? entries.find(locale => locale.toLowerCase() === canonical.language.toLowerCase())
  } catch {
    return undefined
  }
}

export function selectLocale(query: string | null, stored: string | null, languages: readonly string[]): Locale {
  return supportedLocale(query) ?? supportedLocale(stored) ?? languages.map(supportedLocale).find(Boolean) ?? 'en'
}

export function initialLocale(): Locale {
  let stored: string | null = null
  try { stored = localStorage.getItem(localeStorageKey) } catch { /* Storage may be unavailable. */ }
  return selectLocale(new URLSearchParams(window.location.search).get('lang'), stored, navigator.languages ?? [])
}

export function persistLocale(locale: Locale) {
  try { localStorage.setItem(localeStorageKey, locale) } catch { /* The selection still applies for this session. */ }
  const url = new URL(window.location.href)
  if (url.searchParams.has('lang')) {
    url.searchParams.set('lang', locale)
    window.history.replaceState(window.history.state, '', url)
  }
}
