package kz.vainshtein.raftscore.auth.configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Denies a session once its persisted user has been blocked. */
@RequiredArgsConstructor
class UserSessionValidityFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final SessionEpoch sessionEpoch;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var session = request.getSession(false);
        var stale = authentication != null && authentication.isAuthenticated() && session != null
                && sessionEpoch.isStale(session);
        var blocked = !stale && authentication != null && authentication.isAuthenticated()
                && userRepository.findByUsername(authentication.getName())
                .map(user -> !user.isEnabled())
                .orElse(false);
        if (blocked || stale) {
            SecurityContextHolder.clearContext();
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // A concurrent request already invalidated the same session.
                }
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
