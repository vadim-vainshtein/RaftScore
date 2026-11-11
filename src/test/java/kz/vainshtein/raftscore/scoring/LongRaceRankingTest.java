package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class LongRaceRankingTest {
    private final Competition competition = Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build();
    private final CompetitionCategory category = category("Open");
    private final Discipline discipline = Discipline.builder().competition(competition).name("Long race").type(DisciplineType.LONG_RACE).build();
    private final StartList list = StartList.builder().discipline(discipline).category(category).build();

    LongRaceRankingTest() { discipline.replaceCategories(List.of(category)); }

    @Test void ranksTotalsWithSharedPlacesAndPreservesStatuses() {
        var a = crew(category, 1); var b = crew(category, 2); var c = crew(category, 3);
        var d = crew(category, 4); var e = crew(category, 5); var f = crew(category, 6);
        var results = LongRaceRanking.rank(List.of(slot(a, 2), slot(b, 1), slot(c, 3), slot(d, 4), slot(e, 5), slot(f, 6)),
                List.of(finished(b, 90, 10), finished(a, 100, 0), finished(c, Integer.MAX_VALUE, Integer.MAX_VALUE),
                        status(d, AttemptResultStatus.DNS), status(e, AttemptResultStatus.DNF), status(f, AttemptResultStatus.DSQ_COMPETITION)));
        assertThat(results).extracting(LongRaceRanking.Result::crew).containsExactly(a, b, c, d, e, f);
        assertThat(results).extracting(LongRaceRanking.Result::place).containsExactly(1, 1, 3, null, null, null);
        assertThat(results).extracting(LongRaceRanking.Result::totalCentiseconds).containsExactly(100L, 100L, 4294967294L, null, null, null);
        assertThat(results).extracting(LongRaceRanking.Result::status).containsExactly(AttemptResultStatus.FINISHED, AttemptResultStatus.FINISHED, AttemptResultStatus.FINISHED, AttemptResultStatus.DNS, AttemptResultStatus.DNF, AttemptResultStatus.DSQ_COMPETITION);
    }

    @Test void rejectsDuplicateAttemptsMissingSlotsAndMixedCategories() {
        var a = crew(category, 1); var b = crew(category, 2);
        assertThat(LongRaceRanking.rank(List.of(slot(a, 1), slot(b, 2)), List.of(finished(a, 1, 0)))).hasSize(1);
        assertThatThrownBy(() -> LongRaceRanking.rank(List.of(slot(a, 1)), List.of(finished(a, 1, 0), finished(a, 2, 0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongRaceRanking.rank(List.of(slot(a, 1)), List.of(finished(b, 1, 0)))).isInstanceOf(IllegalArgumentException.class);
        var other = category("Other"); var otherList = StartList.builder().discipline(discipline).category(other).build();
        var otherCrew = crew(other, 3);
        assertThatThrownBy(() -> LongRaceRanking.rank(List.of(slot(a, 1), StartSlot.builder().startList(otherList).crew(otherCrew).position(2).build()), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"100, 0, 100, 0, 1", "90, 10, 100, 0, 1", "101, 0, 100, 0, 2"})
    void assignsSharedPlacesFromTotalTime(int firstDuration, int firstPenalty, int secondDuration, int secondPenalty, int firstPlace) {
        var first = crew(category, 1); var second = crew(category, 2);
        var ranked = LongRaceRanking.rank(List.of(slot(first, 1), slot(second, 2)),
                List.of(finished(first, firstDuration, firstPenalty), finished(second, secondDuration, secondPenalty)));
        assertThat(ranked).filteredOn(result -> result.crew() == first)
                .extracting(LongRaceRanking.Result::place).containsExactly(firstPlace);
    }
    private CompetitionCategory category(String name) { return CompetitionCategory.builder().competition(competition).classLabel(name).scoringGroupLabel(name).crewSize(4).build(); }
    private CrewEntry crew(CompetitionCategory c, int number) { return CrewEntry.builder().competitionCategory(c).crewName("Crew " + number).regionOrClub("Club").representative("Person").startNumber(number).build(); }
    private StartSlot slot(CrewEntry crew, int position) { return StartSlot.builder().startList(list).crew(crew).position(position).build(); }
    private AttemptResult finished(CrewEntry crew, int duration, int penalty) { return AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).durationCentiseconds(duration).penaltyCentiseconds(penalty).status(AttemptResultStatus.FINISHED).build(); }
    private AttemptResult status(CrewEntry crew, AttemptResultStatus status) { return AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).status(status).build(); }
}
