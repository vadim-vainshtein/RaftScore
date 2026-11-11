package kz.vainshtein.raftscore.auth.configurations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps normal requests concurrent and makes the destructive restore phase exclusive. */
public class RestoreGateFilter extends OncePerRequestFilter {
    private static final String LEASE = RestoreGateFilter.class.getName() + ".lease";
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Releases this request's read lease and attempts to acquire the exclusive restore lease. */
    public static boolean tryPromote(HttpServletRequest request) {
        var value = request.getAttribute(LEASE);
        if (!(value instanceof Lease lease) || !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/backups/restore").equals(request.getRequestURI())) {
            return false;
        }
        lease.lock.readLock().unlock();
        lease.readHeld = false;
        lease.writeHeld = lease.lock.writeLock().tryLock();
        return lease.writeHeld;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!lock.readLock().tryLock()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"backup.restore_busy\",\"parameters\":{}}");
            return;
        }
        var lease = new Lease(lock);
        request.setAttribute(LEASE, lease);
        try {
            chain.doFilter(request, response);
        } finally {
            request.removeAttribute(LEASE);
            if (lease.writeHeld) lease.lock.writeLock().unlock();
            if (lease.readHeld) lease.lock.readLock().unlock();
        }
    }

    private static final class Lease {
        private final ReentrantReadWriteLock lock;
        private boolean readHeld = true;
        private boolean writeHeld;
        private Lease(ReentrantReadWriteLock lock) { this.lock = lock; }
    }
}
