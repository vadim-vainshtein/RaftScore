package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class ParallelSprintRankingTest {
    private final Competition competition = Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build();
    private final CompetitionCategory category = CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build();
    private final Discipline discipline = Discipline.builder().competition(competition).name("Parallel").type(DisciplineType.PARALLEL_SPRINT).build();
    private final ParallelSprintBracket bracket = ParallelSprintBracket.builder().discipline(discipline).category(category).build();

    @Test
    void ranksResolvedRoundsInStageOrder() {
        var a = crew(1); var b = crew(2); var c = crew(3); var d = crew(4); var e = crew(5);
        var fin = heat(3, a, b, 1, AttemptResultStatus.FINISHED);
        var semi1 = heat(2, a, c, 1, AttemptResultStatus.FINISHED);
        var semi2 = heat(2, b, d, 1, AttemptResultStatus.FINISHED);
        var quarter = heat(1, a, e, 1, AttemptResultStatus.FINISHED);
        var results = ParallelSprintRanking.rank(List.of(quarter, semi2, fin, semi1));
        assertThat(results).extracting(ParallelSprintRanking.Result::crew).containsExactly(a, b, c, d, e);
        assertThat(results).extracting(ParallelSprintRanking.Result::place).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void omitsByesAndUnresolvedHeatsAndKeepsNonfinishersUnplaced() {
        var a = crew(1); var b = crew(2); var c = crew(3);
        var bye = ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(a).winnerSlot(1).build();
        var resolved = heat(1, b, c, 1, AttemptResultStatus.DSQ_DISCIPLINE);
        var finalHeat = ParallelSprintHeat.builder().bracket(bracket).roundNumber(2).heatNumber(1).crew1(a).crew2(b).build();
        var results = ParallelSprintRanking.rank(List.of(bye, resolved, finalHeat));
        assertThat(results).containsExactly(new ParallelSprintRanking.Result(c, AttemptResultStatus.DSQ_DISCIPLINE, null));
    }

    @Test
    void ordersEliminationsByHeatTimeThenStartNumberWithByes() {
        var a = crew(1); var b = crew(2); var c = crew(3); var d = crew(4);
        var e = crew(5); var f = crew(6); var g = crew(7);
        var fin = timedHeat(3, a, b, 500);
        var semi1 = timedHeat(2, a, c, 700);
        var semi2 = timedHeat(2, b, d, 600);
        var quarter1 = timedHeat(1, a, e, 800);
        var quarter2 = timedHeat(1, b, f, 750);
        var quarter3 = timedHeat(1, c, g, 750);
        var bye = ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(4).crew1(d).winnerSlot(1).build();

        var results = ParallelSprintRanking.rank(List.of(quarter3, bye, semi1, quarter1, fin, semi2, quarter2));

        assertThat(results).extracting(ParallelSprintRanking.Result::crew).containsExactly(a, b, d, c, f, g, e);
        assertThat(results).extracting(ParallelSprintRanking.Result::place).containsExactly(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void judicialWinnerWithNonFinishStatusStillWinsFinal() {
        var a = crew(1); var b = crew(2);
        var finalHeat = ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(a).crew2(b).build();
        finalHeat.record(AttemptResultStatus.DNF, null, AttemptResultStatus.DNS, null, 1, true);
        assertThat(ParallelSprintRanking.rank(List.of(finalHeat)))
                .contains(new ParallelSprintRanking.Result(a, AttemptResultStatus.DNF, 1));
    }
    private ParallelSprintHeat timedHeat(int round, CrewEntry winner, CrewEntry loser, int loserTime) {
        return ParallelSprintHeat.builder().bracket(bracket).roundNumber(round).heatNumber(1).crew1(winner).crew2(loser)
                .status1(AttemptResultStatus.FINISHED).status2(AttemptResultStatus.FINISHED)
                .time1Centiseconds(400).time2Centiseconds(loserTime).winnerSlot(1).build();
    }

    @Test
    void breaksEqualTimeAndStartNumberById() {
        var winner = crew(1); var lowerId = crew(2); var higherId = crew(2);
        ReflectionTestUtils.setField(lowerId, "id", 10L);
        ReflectionTestUtils.setField(higherId, "id", 20L);
        var finalHeat = timedHeat(2, winner, crew(3), 500);

        var results = ParallelSprintRanking.rank(List.of(timedHeat(1, winner, higherId, 600),
                finalHeat, timedHeat(1, winner, lowerId, 600)));

        assertThat(results).extracting(ParallelSprintRanking.Result::crew)
                .containsExactly(winner, finalHeat.getCrew2(), lowerId, higherId);
    }

    private CrewEntry crew(int number) { return CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Person").startNumber(number).build(); }
    private ParallelSprintHeat heat(int round, CrewEntry first, CrewEntry second, int winner, AttemptResultStatus loserStatus) {
        return ParallelSprintHeat.builder().bracket(bracket).roundNumber(round).heatNumber(1).crew1(first).crew2(second)
                .status1(winner == 1 ? AttemptResultStatus.FINISHED : loserStatus)
                .status2(winner == 2 ? AttemptResultStatus.FINISHED : loserStatus).winnerSlot(winner).build();
    }
}
