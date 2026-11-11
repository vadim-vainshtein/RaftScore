package kz.vainshtein.raftscore.auth.configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSessionValidityFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blockedRequestsReturnUnauthorizedEvenWhenSessionWasAlreadyInvalidated() throws Exception {
        var repository = mock(UserRepository.class);
        when(repository.findByUsername("reader")).thenReturn(Optional.of(User.builder().enabled(false).build()));
        var filter = new UserSessionValidityFilter(repository);
        var session = mock(HttpSession.class);
        doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();
        var chain = mock(FilterChain.class);

        for (var i = 0; i < 2; i++) {
            var request = mock(MockHttpServletRequest.class);
            when(request.getSession(false)).thenReturn(session);
            var response = new MockHttpServletResponse();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of()));
            filter.doFilterInternal(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            verify(chain, never()).doFilter(request, response);
        }
    }
}
