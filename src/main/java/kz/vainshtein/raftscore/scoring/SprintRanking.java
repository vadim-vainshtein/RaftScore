package kz.vainshtein.raftscore.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.startlist.entities.StartSlot;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;

/** Ranks one category of sprint attempts without persisting results or awarding points. */
public final class SprintRanking {
    private SprintRanking() { }

    public record Result(CrewEntry crew, AttemptResultStatus status, Long totalCentiseconds, Integer place) { }

    /** Every attempt must have a slot; slots without attempts do not yet have a result.
     * Equal totals use start-list order, including when actual start instants are recorded.
     */
    public static List<Result> rank(List<StartSlot> slots, List<AttemptResult> attempts) {
        Map<CrewEntry, StartSlot> byCrew = new HashMap<>();
        var positions = new HashSet<Integer>();
        for (var slot : slots) {
            if (byCrew.put(slot.getCrew(), slot) != null || !positions.add(slot.getPosition())) {
                throw new IllegalArgumentException("duplicate crew or position in start list");
            }
        }
        var seen = new HashSet<CrewEntry>();
        var finished = new ArrayList<AttemptResult>();
        var other = new ArrayList<AttemptResult>();
        for (var attempt : attempts) {
            if (attempt.getAttemptNumber() != 1 || !seen.add(attempt.getCrewEntry())) {
                throw new IllegalArgumentException("sprint requires at most one attempt per crew, numbered 1");
            }
            if (!byCrew.containsKey(attempt.getCrewEntry())) {
                throw new IllegalArgumentException("attempt has no start slot");
            }
            (attempt.getStatus() == AttemptResultStatus.FINISHED ? finished : other).add(attempt);
        }
        Comparator<AttemptResult> slotOrder = Comparator.comparingInt(a -> byCrew.get(a.getCrewEntry()).getPosition());
        finished.sort(Comparator.comparingLong(SprintRanking::total).thenComparing(slotOrder));
        var result = new ArrayList<Result>();
        for (var attempt : finished) {
            result.add(new Result(attempt.getCrewEntry(), attempt.getStatus(), total(attempt), result.size() + 1));
        }
        other.sort(slotOrder);
        for (var attempt : other) result.add(new Result(attempt.getCrewEntry(), attempt.getStatus(), null, null));
        return List.copyOf(result);
    }

    private static long total(AttemptResult attempt) {
        return (long) attempt.getDurationCentiseconds() + attempt.getPenaltyCentiseconds();
    }
}
