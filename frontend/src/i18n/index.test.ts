import {describe, expect, test} from 'vitest'
import i18n from './index'
import {formatDate, formatNumber} from './format'
import {selectLocale, supportedLocale} from './supported-locales'

describe('locale selection', () => {
  test('uses query, storage, browser languages, then English in order', () => {
    expect(selectLocale('ru-RU', 'en', ['en'])).toBe('ru')
    expect(selectLocale('de', 'ru', ['en'])).toBe('ru')
    expect(selectLocale('bad_tag', 'de', ['fr', 'ru-Cyrl-RU', 'en'])).toBe('ru')
    expect(selectLocale(null, null, ['fr'])).toBe('en')
    expect(supportedLocale('english')).toBeUndefined()
  })
  test('falls back to English for missing translations and uses plural rules', async () => {
    await i18n.changeLanguage('ru')
    expect(i18n.t('format.items', {count: 1})).toBe('1 элемент')
    expect(i18n.t('format.items', {count: 2})).toBe('2 элемента')
    expect(i18n.t('format.items', {count: 5})).toBe('5 элементов')
    i18n.addResource('ru', 'common', 'placeholder.unavailable', '')
    expect(i18n.t('placeholder.unavailable', {returnEmptyString: false})).toBe('This section is not available yet.')
    i18n.addResource('ru', 'common', 'placeholder.unavailable', 'Этот раздел пока недоступен.')
    await i18n.changeLanguage('en')
  })
  test('formats numbers and dates in the requested locale and competition timezone', () => {
    expect(formatNumber(1234.5, 'en')).toBe('1,234.5')
    expect(formatDate(new Date('2026-01-01T00:30:00Z'), 'en', 'America/Los_Angeles', {year: 'numeric', month: '2-digit', day: '2-digit'})).toBe('12/31/2025')
  })
})
