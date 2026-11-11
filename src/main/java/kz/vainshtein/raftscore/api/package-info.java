/**
 * API response convention: map persistence entities explicitly to DTOs before
 * returning from a controller. Select only fields intended for the caller; do
 * not expose entities or their sensitive and persistence-managed state. A
 * service or a dedicated mapper may perform the conversion, as appropriate
 * for the feature (see AuthenticationService.principalFor).
 */
package kz.vainshtein.raftscore.api;
