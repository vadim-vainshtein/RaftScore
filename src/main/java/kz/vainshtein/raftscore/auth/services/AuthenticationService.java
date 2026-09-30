package kz.vainshtein.raftscore.auth.services;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kz.vainshtein.raftscore.auth.models.AuthenticatedPrincipal;
import kz.vainshtein.raftscore.auth.models.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

/** Authenticates credentials and stores successful authentication in the server session. */
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public boolean authenticate(
            String username,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return true;
        } catch (AuthenticationException exception) {
            return false;
        }
    }

    public AuthenticatedPrincipal principalFor(Authentication authentication) {
        return new AuthenticatedPrincipal(authentication.getName(), roleFrom(authentication));
    }

    private UserRole roleFrom(Authentication authentication) {
        var authority = authentication.getAuthorities().stream()
                .findFirst()
                .orElseThrow()
                .getAuthority();
        if (!authority.startsWith(ROLE_PREFIX)) {
            throw new IllegalStateException("Authenticated principal is missing a role authority.");
        }
        return UserRole.valueOf(authority.substring(ROLE_PREFIX.length()));
    }
}
