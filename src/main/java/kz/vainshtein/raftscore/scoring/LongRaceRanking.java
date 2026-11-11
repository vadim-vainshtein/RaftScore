package kz.vainshtein.raftscore.scoring;

import java.util.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.startlist.entities.StartSlot;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;

/** Ranks one category of long-race attempts without persisting results or awarding points. */
public final class LongRaceRanking {
    private LongRaceRanking() { }

    public record Result(CrewEntry crew, AttemptResultStatus status, Long totalCentiseconds, Integer place) { }

    /** Equal totals share competition places (1, 1, 3); start number and ID determine display order.
     * Every attempt must have a slot, and slots without attempts have no result yet.
     */
    public static List<Result> rank(List<StartSlot> slots, List<AttemptResult> attempts) {
        var crews = new HashSet<CrewEntry>();
        var positions = new HashSet<Integer>();
        for (var slot : slots) {
            if (!crews.add(slot.getCrew()) || !positions.add(slot.getPosition()))
                throw new IllegalArgumentException("duplicate crew or position in start list");
            if (!sameCategory(slots.getFirst().getCategory(), slot.getCategory()))
                throw new IllegalArgumentException("slots must belong to one category");
        }
        var seen = new HashSet<CrewEntry>();
        var finished = new ArrayList<AttemptResult>();
        var other = new ArrayList<AttemptResult>();
        for (var attempt : attempts) {
            if (attempt.getAttemptNumber() != 1 || !seen.add(attempt.getCrewEntry()))
                throw new IllegalArgumentException("long race requires at most one attempt per crew, numbered 1");
            if (!crews.contains(attempt.getCrewEntry()))
                throw new IllegalArgumentException("attempt has no start slot");
            (attempt.getStatus() == AttemptResultStatus.FINISHED ? finished : other).add(attempt);
        }
        Comparator<AttemptResult> displayOrder = Comparator.comparingInt((AttemptResult a) -> a.getCrewEntry().getStartNumber())
                .thenComparing(a -> a.getCrewEntry().getId(), Comparator.nullsLast(Long::compareTo));
        finished.sort(Comparator.comparingLong(LongRaceRanking::total).thenComparing(displayOrder));
        other.sort(displayOrder);
        var result = new ArrayList<Result>();
        Long previous = null;
        int place = 0;
        for (var attempt : finished) {
            long total = total(attempt);
            if (previous == null || previous != total) place = result.size() + 1;
            result.add(new Result(attempt.getCrewEntry(), attempt.getStatus(), total, place));
            previous = total;
        }
        for (var attempt : other) result.add(new Result(attempt.getCrewEntry(), attempt.getStatus(), null, null));
        return List.copyOf(result);
    }

    private static boolean sameCategory(kz.vainshtein.raftscore.competition.entities.CompetitionCategory a,
                                        kz.vainshtein.raftscore.competition.entities.CompetitionCategory b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }

    private static long total(AttemptResult attempt) {
        return (long) attempt.getDurationCentiseconds() + attempt.getPenaltyCentiseconds();
    }
}
