package kz.vainshtein.raftscore.auth.models;

/** Safe representation of the authenticated user for the client. */
public record AuthenticatedPrincipal(String username, UserRole role) {
}
