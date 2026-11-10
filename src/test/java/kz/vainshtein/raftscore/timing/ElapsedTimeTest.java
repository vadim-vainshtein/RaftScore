package kz.vainshtein.raftscore.timing;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ElapsedTimeTest {

    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void calculatesWholeCentisecondsAndTruncatesRemainders() {
        assertThat(ElapsedTime.centisecondsBetween(START, START)).isZero();
        assertThat(ElapsedTime.centisecondsBetween(START, START.plusNanos(9_999_999))).isZero();
        assertThat(ElapsedTime.centisecondsBetween(START, START.plusNanos(10_000_000))).isEqualTo(1);
        assertThat(ElapsedTime.centisecondsBetween(START, START.plusNanos(19_999_999))).isEqualTo(1);
        assertThat(ElapsedTime.centisecondsBetween(START, START.plusSeconds(12).plusNanos(345_678_901)))
                .isEqualTo(1234);
    }

    @Test
    void rejectsFinishBeforeStartEvenWithinOneCentisecond() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ElapsedTime.centisecondsBetween(START, START.minusNanos(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ElapsedTime.centisecondsBetween(START, START.minusSeconds(2)));
    }

    @Test
    void handlesIntervalsTooLongToRepresentAsNanoseconds() {
        assertThat(ElapsedTime.centisecondsBetween(START, START.plusSeconds(10_000_000_000L)))
                .isEqualTo(1_000_000_000_000L);
    }
}
