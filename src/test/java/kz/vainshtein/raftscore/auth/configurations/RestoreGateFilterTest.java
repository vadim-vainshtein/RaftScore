package kz.vainshtein.raftscore.auth.configurations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RestoreGateFilterTest {
    @Test
    void restoreExcludesOrdinaryRequestsAndReleasesLease() throws Exception {
        var filter = new RestoreGateFilter();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var restore = executor.submit(() -> {
                var request = new MockHttpServletRequest("POST", "/api/backups/restore");
                filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    assertThat(RestoreGateFilter.tryPromote((jakarta.servlet.http.HttpServletRequest) req)).isTrue();
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var busy = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/test"), busy, (req, res) -> {});
                assertThat(busy.getStatus()).isEqualTo(503);
                assertThat(busy.getContentAsString()).isEqualTo("{\"code\":\"backup.restore_busy\",\"parameters\":{}}");
            } finally {
                release.countDown();
            }
            restore.get(5, TimeUnit.SECONDS);
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/test"), response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
    @Test
    void ordinaryRequestsProceedBeforePromotion() throws Exception {
        var filter = new RestoreGateFilter();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var restore = executor.submit(() -> {
                filter.doFilter(new MockHttpServletRequest("POST", "/api/backups/restore"),
                        new MockHttpServletResponse(), (req, res) -> {
                            entered.countDown();
                            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        });
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/test"), response, (req, res) -> {});
                assertThat(response.getStatus()).isEqualTo(200);
            } finally {
                release.countDown();
            }
            restore.get(5, TimeUnit.SECONDS);
        }
    }}
