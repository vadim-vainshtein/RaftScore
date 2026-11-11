import 'i18next'
import en from './locales/en/common.json'

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common'
    resources: {common: typeof en}
    strictKeyChecks: true
  }
}
