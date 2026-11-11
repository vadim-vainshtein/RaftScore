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
    void databaseOutageStopsRequestWithSafeErrorAndAllowsRecovery() throws Exception {
        var repository = mock(UserRepository.class);
        when(repository.findByUsername("reader"))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("password and SQL"))
                .thenReturn(Optional.empty());
        var filter = new UserSessionValidityFilter(repository, new SessionEpoch(), new tools.jackson.databind.ObjectMapper());
        var request = new MockHttpServletRequest();
        var chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of()));
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("database.unavailable").doesNotContain("password", "SQL");
        verify(chain, never()).doFilter(request, response);

        var recovered = new MockHttpServletResponse();
        filter.doFilterInternal(request, recovered, chain);
        verify(chain).doFilter(request, recovered);
    }
    @Test
    void blockedRequestsReturnUnauthorizedEvenWhenSessionWasAlreadyInvalidated() throws Exception {
        var repository = mock(UserRepository.class);
        when(repository.findByUsername("reader")).thenReturn(Optional.of(User.builder().enabled(false).build()));
        var filter = new UserSessionValidityFilter(repository, new SessionEpoch(), new tools.jackson.databind.ObjectMapper());
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
    @Test
    void invalidatesStampedSessionsButAllowsUnstampedSyntheticAuthentication() throws Exception {
        var repository = mock(UserRepository.class);
        var epoch = new SessionEpoch();
        var filter = new UserSessionValidityFilter(repository, epoch, new tools.jackson.databind.ObjectMapper());
        var stale = new MockHttpServletRequest();
        epoch.stamp(stale.getSession());
        epoch.invalidateAll();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of()));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilterInternal(stale, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(stale, response);

        var synthetic = new MockHttpServletRequest();
        synthetic.getSession();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("reader", "", java.util.List.of()));
        var allowed = new MockHttpServletResponse();
        filter.doFilterInternal(synthetic, allowed, chain);
        verify(chain).doFilter(synthetic, allowed);
    }}
