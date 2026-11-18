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

    private record Elimination(CrewEntry crew, AttemptResultStatus status, int stagePlace, Integer time) { }

    public static List<Result> rank(List<ParallelSprintHeat> heats) {
        return rank(heats, crew -> true);
    }

    public static List<Result> rank(List<ParallelSprintHeat> heats, java.util.function.Predicate<CrewEntry> eligible) {
        int finalRound = heats.stream().mapToInt(ParallelSprintHeat::getRoundNumber).max().orElse(0);
        boolean hasBronze = heats.stream().anyMatch(h -> h.getRoundNumber() == finalRound && h.getHeatNumber() == 2);
        var results = new ArrayList<Result>();
        var eliminations = new ArrayList<Elimination>();
        for (var heat : heats) {
            if ((heat.getCrew1() != null && !eligible.test(heat.getCrew1()))
                    || (heat.getCrew2() != null && !eligible.test(heat.getCrew2()))) continue;
            if (heat.getRoundNumber() == finalRound && heat.getNextHeat() == null && heat.outcomeResolved()
                    && heat.winner() != null && (heat.getCrew1() == null || heat.getCrew2() == null)) {
                var winner = heat.winner();
                var status = latestStatus(heats, winner, finalRound, eligible);
                if (status != null) results.add(new Result(winner, status,
                        status == AttemptResultStatus.FINISHED ? (heat.getHeatNumber() == 2 ? 3 : 1) : null));
                continue;
            }
            if (heat.getWinnerSlot() == null || heat.getCrew1() == null || heat.getCrew2() == null
                    || heat.getStatus1() == null || heat.getStatus2() == null) continue;
            if (hasBronze && heat.getRoundNumber() == finalRound - 1) continue;
            boolean bronze = hasBronze && heat.getRoundNumber() == finalRound && heat.getHeatNumber() == 2;
            boolean firstWon = heat.getWinnerSlot() == 1;
            var loser = firstWon ? heat.getCrew2() : heat.getCrew1();
            var loserStatus = firstWon ? heat.getStatus2() : heat.getStatus1();
            int stagePlace = bronze ? 4 : (1 << (finalRound - heat.getRoundNumber())) + 1;
            var time = firstWon ? heat.getTime2Centiseconds() : heat.getTime1Centiseconds();
            eliminations.add(new Elimination(loser, loserStatus, stagePlace, time));
            if (heat.getRoundNumber() == finalRound && heat.getNextHeat() == null) {
                var winner = firstWon ? heat.getCrew1() : heat.getCrew2();
                var winnerStatus = firstWon ? heat.getStatus1() : heat.getStatus2();
                if (winnerStatus == AttemptResultStatus.FINISHED || heat.isWinnerOverridden()) results.add(new Result(winner, winnerStatus, bronze ? 3 : 1));
            }
        }
        eliminations.sort(Comparator.comparingInt(Elimination::stagePlace)
                .thenComparing(Elimination::time, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> e.crew().getStartNumber())
                .thenComparing(e -> e.crew().getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        int previousStage = -1;
        int place = 0;
        for (var elimination : eliminations) {
            if (elimination.stagePlace() != previousStage) {
                previousStage = elimination.stagePlace();
                place = previousStage;
            }
            Integer resultPlace = elimination.status() == AttemptResultStatus.FINISHED ? place++ : null;
            results.add(new Result(elimination.crew(), elimination.status(), resultPlace));
        }
        results.sort(Comparator.comparing(Result::place, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.crew().getStartNumber())
                .thenComparing(r -> r.crew().getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(results);
    }

    private static AttemptResultStatus latestStatus(List<ParallelSprintHeat> heats, CrewEntry crew, int beforeRound,
                                                    java.util.function.Predicate<CrewEntry> eligible) {
        return heats.stream().filter(h -> h.getRoundNumber() < beforeRound)
                .filter(h -> (h.getCrew1() == null || eligible.test(h.getCrew1())) && (h.getCrew2() == null || eligible.test(h.getCrew2())))
                .sorted(Comparator.comparingInt(ParallelSprintHeat::getRoundNumber).reversed())
                .map(h -> h.getCrew1() != null && h.getCrew1().getId().equals(crew.getId()) ? h.getStatus1()
                        : h.getCrew2() != null && h.getCrew2().getId().equals(crew.getId()) ? h.getStatus2() : null)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }
}
