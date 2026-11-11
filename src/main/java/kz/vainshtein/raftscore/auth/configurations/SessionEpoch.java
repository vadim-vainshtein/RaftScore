package kz.vainshtein.raftscore.auth.configurations;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Tracks sessions created before a successful database restore. */
@Component
public class SessionEpoch {
    private static final String ATTRIBUTE = SessionEpoch.class.getName();
    private final AtomicLong epoch = new AtomicLong();

    public void stamp(HttpSession session) {
        session.setAttribute(ATTRIBUTE, epoch.get());
    }

    public long current() {
        return epoch.get();
    }

    public void invalidateAll() {
        epoch.incrementAndGet();
    }

    boolean isStale(HttpSession session) {
        var value = session.getAttribute(ATTRIBUTE);
        return value instanceof Long generation && generation.longValue() != epoch.get();
    }
}
