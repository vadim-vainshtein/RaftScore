import i18n from 'i18next'
import {initReactI18next} from 'react-i18next'
import {initialLocale, locales} from './supported-locales'

const resources = Object.fromEntries(Object.entries(locales).map(([locale, config]) => [locale, {common: config.common, auth: config.auth}]))
void i18n.use(initReactI18next).init({
  resources, defaultNS: 'common',
  lng: initialLocale(), fallbackLng: 'en', interpolation: {escapeValue: false},
})

export default i18n
