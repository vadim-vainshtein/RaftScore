import {ApiError} from './client'

// Only known codes are exposed; server parameters and details are never rendered.
const codes = {
  'request.validation_failed': 'validation', 'request.malformed': 'validation',
  'conflict.optimistic_lock': 'conflict', 'competition_category.duplicate': 'duplicate',
  'crew_entry.participant_duplicate': 'participantDuplicate', 'crew_entry.start_number_duplicate': 'startNumberDuplicate',
  'crew_entry.invalid_roster': 'invalidRoster', 'crew_entry.invalid_requirement_answers': 'invalidRequirements',
  'start_list.invalid': 'validation', 'access.forbidden': 'access', 'database.unavailable': 'server',
  'backup.confirmation_required': 'validation', 'backup.reauthentication_failed': 'access',
  'backup.restore_busy': 'conflict', 'backup.restore_failed': 'server',
  'category_template.archived': 'conflict', 'category_template.not_found': 'notFound',
  'competition_category.not_found': 'notFound', 'competition.not_found': 'notFound',
  'crew_entry.not_found': 'notFound', 'crew_entry.admission_not_required': 'validation',
  'discipline_or_points_table.not_found': 'notFound', 'discipline.points_table_assigned': 'conflict',
} as const
export type ApiErrorKey = `apiError.${typeof codes[keyof typeof codes] | 'network' | 'validation' | 'access' | 'notFound' | 'conflict' | 'server' | 'generic'}`
export function apiErrorKey(error: unknown): ApiErrorKey {
  if (!(error instanceof ApiError)) return 'apiError.network'
  if (error.code && Object.hasOwn(codes, error.code)) return `apiError.${codes[error.code as keyof typeof codes]}`
  if (error.status === 400 || error.status === 422) return 'apiError.validation'
  if (error.status === 401 || error.status === 403) return 'apiError.access'
  if (error.status === 404) return 'apiError.notFound'
  if (error.status === 409) return 'apiError.conflict'
  if (error.status >= 500) return 'apiError.server'
  return 'apiError.generic'
}
export type FieldErrorKey = 'apiError.required' | 'apiError.invalidField'
export function fieldErrorKey(code: unknown): FieldErrorKey {
  return code === 'validation.required' ? 'apiError.required' : 'apiError.invalidField'
}
