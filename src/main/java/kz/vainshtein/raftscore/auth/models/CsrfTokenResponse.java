package kz.vainshtein.raftscore.auth.models;

/** CSRF token metadata required by the browser client for state-changing requests. */
public record CsrfTokenResponse(String headerName, String parameterName, String token) {
}
