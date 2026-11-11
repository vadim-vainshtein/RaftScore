import {describe, expect, test} from 'vitest'
import {ApiError} from './client'
import {apiErrorKey, fieldErrorKey} from './errors'
import i18n from '../i18n'

describe('safe localized API errors', () => {
  test.each([[400, 'validation'], [401, 'access'], [403, 'access'], [404, 'notFound'], [409, 'conflict'], [500, 'server'], [418, 'generic']])('maps HTTP %s', (status, key) => {
    expect(apiErrorKey(new ApiError(status as number, {message: 'secret'}))).toBe(`apiError.${key}`)
  })
  test('uses known machine and field codes without displaying server details', async () => {
    const error = new ApiError(409, {code: 'crew_entry.start_number_duplicate', parameters: {detail: 'secret'}})
    expect(apiErrorKey(error)).toBe('apiError.startNumberDuplicate')
    expect(fieldErrorKey('validation.required')).toBe('apiError.required')
    expect(fieldErrorKey('untrusted secret')).toBe('apiError.invalidField')
    expect(apiErrorKey(new ApiError(418, {code: 'constructor', parameters: {}}))).toBe('apiError.generic')
    expect(apiErrorKey(new Error('secret'))).toBe('apiError.network')
    await i18n.changeLanguage('ru')
    expect(i18n.t('apiError.startNumberDuplicate')).toContain('номер')
    await i18n.changeLanguage('en')
    expect(i18n.t('apiError.startNumberDuplicate')).toContain('number')
  })
})
