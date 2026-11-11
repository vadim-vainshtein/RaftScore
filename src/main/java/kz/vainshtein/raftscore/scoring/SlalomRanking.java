package kz.vainshtein.raftscore.scoring;

import java.util.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.slalom.SlalomAttemptCalculator;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfiguration;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty;
import kz.vainshtein.raftscore.startlist.entities.StartSlot;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;

/** Ranks a category without persisting results or awarding points. */
public final class SlalomRanking {
    private SlalomRanking() { }

    public record Result(CrewEntry crew, AttemptResultStatus status, Long totalCentiseconds, Integer place) { }
    private record Finished(long total, long penalty) { }
    private record Candidate(CrewEntry crew, int position, AttemptResultStatus status, Finished best, Long worst) { }

    /** Missing or non-finishing attempts are worse than every finished attempt for the worst-attempt tie break.
     * A crew with no recorded attempts has no result. Competition disqualification takes precedence over discipline
     * disqualification; otherwise any finish makes the crew eligible, and remaining statuses use the same precedence
     * as the attempt status list below.
     */
    public static List<Result> rank(SlalomConfiguration configuration, List<StartSlot> slots,
                                    List<AttemptResult> attempts, List<SlalomGatePenalty> penalties) {
        Map<CrewEntry, StartSlot> byCrew = new HashMap<>();
        var positions = new HashSet<Integer>();
        for (var slot : slots) {
            if (byCrew.put(slot.getCrew(), slot) != null || !positions.add(slot.getPosition()))
                throw new IllegalArgumentException("duplicate crew or position in start list");
        }
        Map<AttemptResult, List<SlalomGatePenalty>> byAttempt = new HashMap<>();
        for (var penalty : penalties) byAttempt.computeIfAbsent(penalty.getAttempt(), ignored -> new ArrayList<>()).add(penalty);
        Map<CrewEntry, List<AttemptResult>> crews = new HashMap<>();
        var attemptSet = new HashSet<AttemptResult>();
        for (var attempt : attempts) {
            if (!byCrew.containsKey(attempt.getCrewEntry())) throw new IllegalArgumentException("attempt has no start slot");
            if (!attemptSet.add(attempt)) throw new IllegalArgumentException("duplicate attempt");
            crews.computeIfAbsent(attempt.getCrewEntry(), ignored -> new ArrayList<>()).add(attempt);
        }
        if (!attemptSet.containsAll(byAttempt.keySet())) throw new IllegalArgumentException("penalty has no attempt");
        var candidates = new ArrayList<Candidate>();
        for (var entry : crews.entrySet()) {
            candidates.add(candidate(configuration, entry.getKey(), byCrew.get(entry.getKey()).getPosition(), entry.getValue(), byAttempt));
        }
        candidates.sort(Comparator.comparing((Candidate c) -> c.best() == null)
                .thenComparing(c -> c.best() == null ? null : c.best().total(), Comparator.nullsLast(Long::compareTo))
                .thenComparing(Candidate::worst, Comparator.nullsLast(Long::compareTo))
                .thenComparing(c -> c.best() == null ? null : c.best().penalty(), Comparator.nullsLast(Long::compareTo))
                .thenComparingInt(Candidate::position));
        var result = new ArrayList<Result>();
        int place = 0;
        for (var c : candidates) result.add(new Result(c.crew(), c.status(), c.best() == null ? null : c.best().total(), c.best() == null ? null : ++place));
        return List.copyOf(result);
    }

    private static Candidate candidate(SlalomConfiguration configuration, CrewEntry crew, int position,
                                       List<AttemptResult> attempts, Map<AttemptResult, List<SlalomGatePenalty>> penalties) {
        var numbers = new HashSet<Integer>();
        var finished = new ArrayList<Finished>();
        AttemptResultStatus status = AttemptResultStatus.DNS;
        for (var attempt : attempts) {
            if (!numbers.add(attempt.getAttemptNumber())) throw new IllegalArgumentException("duplicate attempt number for crew");
            var gates = penalties.getOrDefault(attempt, List.of());
            var calculated = SlalomAttemptCalculator.calculate(configuration, attempt, gates);
            if (calculated.status() == AttemptResultStatus.FINISHED) {
                long penalty = calculated.totalCentiseconds() - attempt.getDurationCentiseconds().longValue();
                finished.add(new Finished(calculated.totalCentiseconds(), penalty));
            }
            if (priority(calculated.status()) > priority(status)) status = calculated.status();
        }
        if (status == AttemptResultStatus.DSQ_COMPETITION || status == AttemptResultStatus.DSQ_DISCIPLINE || finished.isEmpty())
            return new Candidate(crew, position, status, null, null);
        finished.sort(Comparator.comparingLong(Finished::total).thenComparingLong(Finished::penalty));
        Long worst = finished.size() == configuration.getAttemptCount() ? finished.getLast().total() : null;
        return new Candidate(crew, position, AttemptResultStatus.FINISHED, finished.getFirst(), worst);
    }

    private static int priority(AttemptResultStatus status) {
        return switch (status) {
            case DNS -> 0;
            case DNF -> 1;
            case DSQ_ATTEMPT -> 2;
            case FINISHED -> 3;
            case DSQ_DISCIPLINE -> 4;
            case DSQ_COMPETITION -> 5;
        };
    }
}
