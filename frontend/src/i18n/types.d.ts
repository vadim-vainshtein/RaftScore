import 'i18next'
import en from './locales/en/common.json'
import auth from './locales/en/auth.json'

declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common'
    resources: {common: typeof en, auth: typeof auth}
    strictKeyChecks: true
  }
}
