package kz.vainshtein.raftscore.auth.configurations;

import kz.vainshtein.raftscore.auth.models.UserRole;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Reusable authorization policies for server API endpoints. */
final class AuthorizationPolicies {

    private AuthorizationPolicies() {
    }

    static AuthorizationManager<RequestAuthorizationContext> authenticated() {
        return AuthenticatedAuthorizationManager.authenticated();
    }

    static AuthorizationManager<RequestAuthorizationContext> fullAccess() {
        return AuthorityAuthorizationManager.hasRole(UserRole.FULL_ACCESS.name());
    }
}
