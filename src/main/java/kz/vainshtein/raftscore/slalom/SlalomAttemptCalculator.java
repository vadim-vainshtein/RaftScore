package kz.vainshtein.raftscore.slalom;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfiguration;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;

/** Calculates one attempt without assigning a place or modifying stored results. */
public final class SlalomAttemptCalculator {
    private SlalomAttemptCalculator() { }

    public record Result(AttemptResultStatus status, Long totalCentiseconds) { }

    public static Result calculate(SlalomConfiguration configuration, AttemptResult attempt,
                                   List<SlalomGatePenalty> penalties) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(penalties, "penalties");
        if (!same(configuration.getDiscipline(), attempt.getDiscipline(),
                configuration.getDiscipline().getId(), attempt.getDiscipline().getId())) {
            throw new IllegalArgumentException("attempt must belong to the configured discipline");
        }
        if (attempt.getAttemptNumber() <= 0 || attempt.getAttemptNumber() > configuration.getAttemptCount()) {
            throw new IllegalArgumentException("attempt number outside configured range");
        }
        if (attempt.getPenaltyCentiseconds() != 0) {
            throw new IllegalArgumentException("slalom penalties must be recorded per gate");
        }
        var gates = new HashSet<Integer>();
        long penaltyCentiseconds = 0;
        for (var penalty : penalties) {
            Objects.requireNonNull(penalty, "penalty");
            if (!same(configuration, penalty.getConfiguration(), configuration.getId(), penalty.getConfiguration().getId())
                    || !same(attempt, penalty.getAttempt(), attempt.getId(), penalty.getAttempt().getId())) {
                throw new IllegalArgumentException("gate penalty belongs to another configuration or attempt");
            }
            var gate = penalty.getGateNumber();
            var seconds = penalty.getPenaltySeconds();
            if (gate <= 0 || gate > configuration.getGateCount() || !gates.add(gate)) {
                throw new IllegalArgumentException("invalid or duplicate gate number");
            }
            if (seconds != 0 && seconds != 5 && seconds != 50) {
                throw new IllegalArgumentException("invalid gate penalty");
            }
            penaltyCentiseconds = Math.addExact(penaltyCentiseconds, seconds * 100L);
        }
        var status = Objects.requireNonNull(attempt.getStatus(), "status");
        if (status == AttemptResultStatus.DNS && !gates.isEmpty()) {
            throw new IllegalArgumentException("DNS cannot have gate penalties");
        }
        if (status != AttemptResultStatus.FINISHED) return new Result(status, null);
        if (gates.size() != configuration.getGateCount() || attempt.getDurationCentiseconds() == null
                || attempt.getDurationCentiseconds() < 0) {
            throw new IllegalArgumentException("finished attempt requires a duration and every gate");
        }
        return new Result(status, Math.addExact(attempt.getDurationCentiseconds().longValue(), penaltyCentiseconds));
    }

    private static boolean same(Object first, Object second, Long firstId, Long secondId) {
        return first == second || (firstId != null && firstId.equals(secondId));
    }
}
