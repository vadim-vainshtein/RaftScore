package kz.vainshtein.raftscore.scoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;

/** Derives current results from resolved heats; crews advancing by a bye are not eliminated. */
public final class ParallelSprintRanking {
    private ParallelSprintRanking() { }

    public record Result(CrewEntry crew, AttemptResultStatus status, Integer place) { }

    public static List<Result> rank(List<ParallelSprintHeat> heats) {
        int finalRound = heats.stream().mapToInt(ParallelSprintHeat::getRoundNumber).max().orElse(0);
        var results = new ArrayList<Result>();
        for (var heat : heats) {
            if (heat.getWinnerSlot() == null || heat.getCrew1() == null || heat.getCrew2() == null
                    || heat.getStatus1() == null || heat.getStatus2() == null) continue;
            boolean firstWon = heat.getWinnerSlot() == 1;
            var loser = firstWon ? heat.getCrew2() : heat.getCrew1();
            var loserStatus = firstWon ? heat.getStatus2() : heat.getStatus1();
            int stagePlace = (1 << (finalRound - heat.getRoundNumber())) + 1;
            results.add(new Result(loser, loserStatus, loserStatus == AttemptResultStatus.FINISHED ? stagePlace : null));
            if (heat.getRoundNumber() == finalRound && heat.getNextHeat() == null) {
                var winner = firstWon ? heat.getCrew1() : heat.getCrew2();
                var winnerStatus = firstWon ? heat.getStatus1() : heat.getStatus2();
                if (winnerStatus == AttemptResultStatus.FINISHED) results.add(new Result(winner, winnerStatus, 1));
            }
        }
        results.sort(Comparator.comparing(Result::place, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.crew().getStartNumber()));
        return List.copyOf(results);
    }
}
