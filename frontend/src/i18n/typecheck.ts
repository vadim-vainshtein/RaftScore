import type {TFunction} from 'i18next'

// Compile-time contract: even a default value must not permit an unknown key.
export function checkTranslationKeys(t: TFunction) {
  // @ts-expect-error Unknown translation keys are rejected.
  t('navigation.competitons', {defaultValue: 'Competitions'})
}
