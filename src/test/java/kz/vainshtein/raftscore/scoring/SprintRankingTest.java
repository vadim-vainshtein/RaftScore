package kz.vainshtein.raftscore.scoring;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SprintRankingTest {
    private final Competition competition = Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build();
    private final CompetitionCategory category = CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build();
    private final Discipline discipline = Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build();
    private final StartList list = StartList.builder().discipline(discipline).category(category).build();

    SprintRankingTest() { discipline.replaceCategories(List.of(category)); }

    @Test
    void ranksTotalAndPreservesOtherStatuses() {
        var a = crew(1); var b = crew(2); var c = crew(3); var d = crew(4);
        var results = SprintRanking.rank(List.of(slot(a, 1), slot(b, 2), slot(c, 3), slot(d, 4)),
                List.of(attempt(a, 100, 20), attempt(b, 110, 0), attempt(c, 120, 0),
                        AttemptResult.builder().discipline(discipline).crewEntry(d).attemptNumber(1).status(AttemptResultStatus.DNF).build()));
        assertThat(results).extracting(SprintRanking.Result::crew).containsExactly(b, a, c, d);
        assertThat(results).extracting(SprintRanking.Result::place).containsExactly(1, 2, 3, null);
        assertThat(results).extracting(SprintRanking.Result::totalCentiseconds).containsExactly(110L, 120L, 120L, null);
        assertThat(results.get(3).status()).isEqualTo(AttemptResultStatus.DNF);
    }

    @Test
    void usesStartOrderForEqualTotalsEvenWhenActualStartsDiffer() {
        var a = crew(1); var b = crew(2);
        var results = SprintRanking.rank(List.of(slot(a, 1), slot(b, 2)), List.of(timed(a, "2026-01-01T00:01:00Z"), timed(b, "2026-01-01T00:00:00Z")));
        assertThat(results).extracting(SprintRanking.Result::crew).containsExactly(a, b);
    }

    @Test
    void missingAttemptsAreOmittedAndMissingSlotsOrExtraAttemptsAreRejected() {
        var a = crew(1); var b = crew(2);
        assertThat(SprintRanking.rank(List.of(slot(a, 1), slot(b, 2)), List.of(attempt(a, 1, 0)))).hasSize(1);
        assertThatThrownBy(() -> SprintRanking.rank(List.of(slot(a, 1)), List.of(attempt(b, 1, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SprintRanking.rank(List.of(slot(a, 1)), List.of(attempt(a, 1, 0), attempt(a, 2, 0)))).isInstanceOf(IllegalArgumentException.class);
    }

    private CrewEntry crew(int number) { return CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Person").startNumber(number).build(); }
    private StartSlot slot(CrewEntry crew, int position) { return StartSlot.builder().startList(list).crew(crew).position(position).build(); }
    private AttemptResult attempt(CrewEntry crew, int duration, int penalty) { return AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).durationCentiseconds(duration).penaltyCentiseconds(penalty).status(AttemptResultStatus.FINISHED).build(); }
    private AttemptResult timed(CrewEntry crew, String start) { var instant = Instant.parse(start); return AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).startInstant(instant).finishInstant(instant.plusSeconds(1)).status(AttemptResultStatus.FINISHED).build(); }
}
