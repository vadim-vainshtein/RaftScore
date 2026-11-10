package kz.vainshtein.raftscore.timing;

import java.time.Duration;
import java.time.Instant;

/** Calculates elapsed time from UTC instants in whole centiseconds. */
public final class ElapsedTime {

    private static final Duration CENTISECOND = Duration.ofMillis(10);

    private ElapsedTime() {
    }

    /** Returns elapsed centiseconds, discarding any positive fractional remainder. */
    public static long centisecondsBetween(Instant start, Instant finish) {
        if (finish.isBefore(start)) {
            throw new IllegalArgumentException("Finish must not be before start");
        }
        return Duration.between(start, finish).dividedBy(CENTISECOND);
    }
}
