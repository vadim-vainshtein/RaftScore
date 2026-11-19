package kz.vainshtein.raftscore.competition;

import java.util.List;
import java.util.Optional;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.entry.services.CrewEligibilityService;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompletionCheckTest {
    private final DisciplineRepository disciplines = mock();
    private final CrewEntryRepository crews = mock();
    private final CrewEligibilityService eligibility = mock();
    private final StartListRepository lists = mock();
    private final StartSlotRepository slots = mock();
    private final AttemptResultRepository attempts = mock();
    private final SlalomConfigurationRepository slalom = mock();
    private final SlalomGatePenaltyRepository penalties = mock();
    private final ParallelSprintBracketRepository brackets = mock();
    private final ParallelSprintHeatRepository heats = mock();
    private final kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository calculated = mock();
    private final CompletionCheck check = new CompletionCheck(disciplines, crews, eligibility, lists, slots, attempts, slalom, penalties, brackets, heats, calculated);

    @Test
    void listsTheMissingCrewAndAcceptsAnExplicitNonfinish() {
        var d = discipline(DisciplineType.SPRINT); var category = category(d);
        var first = crew(category, 11L); var second = crew(category, 12L);
        var list = mock(StartList.class); when(list.getId()).thenReturn(3L);
        when(lists.findByDisciplineIdAndCategoryId(1L, 2L)).thenReturn(Optional.of(list));
        var slot1 = mock(StartSlot.class); when(slot1.getCrew()).thenReturn(first);
        var slot2 = mock(StartSlot.class); when(slot2.getCrew()).thenReturn(second);
        when(slots.findByStartListIdOrderByPositionAsc(3L)).thenReturn(List.of(slot1, slot2));
        var a = attempt(first, 1, AttemptResultStatus.FINISHED);
        when(attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(1L, 2L)).thenReturn(List.of(a));
        assertThat(check.discipline(d)).singleElement().satisfies(issue -> {
            assertThat(issue.crewId()).isEqualTo(12L);
            assertThat(issue.code()).isEqualTo("missingAttempt"); assertThat(issue.attempt()).isEqualTo(1);
        });
        when(disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(44L)).thenReturn(List.of(d));
        assertThat(check.progress(44L)).containsExactly(new CompletionCheck.Progress(1L, 1, 2));
        var b = attempt(second, 1, AttemptResultStatus.DNF);
        when(attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(1L, 2L)).thenReturn(List.of(a, b));
        assertThat(check.discipline(d)).isEmpty();
        assertThat(check.progress(44L)).containsExactly(new CompletionCheck.Progress(1L, 2, 2));
    }

    @Test
    void requiresEverySlalomAttempt() {
        var d = discipline(DisciplineType.SLALOM); var category = category(d); var crew = crew(category, 11L);
        var list = mock(StartList.class); when(list.getId()).thenReturn(3L);
        when(lists.findByDisciplineIdAndCategoryId(1L, 2L)).thenReturn(Optional.of(list));
        var slot = mock(StartSlot.class); when(slot.getCrew()).thenReturn(crew);
        when(slots.findByStartListIdOrderByPositionAsc(3L)).thenReturn(List.of(slot));
        var config = mock(SlalomConfiguration.class); when(config.getAttemptCount()).thenReturn(2);
        when(slalom.findByDisciplineId(1L)).thenReturn(Optional.of(config));
        var first = attempt(crew, 1, AttemptResultStatus.DNF);
        when(attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(1L, 2L)).thenReturn(List.of(first));
        assertThat(check.discipline(d)).singleElement().satisfies(issue -> { assertThat(issue.code()).isEqualTo("missingAttempt"); assertThat(issue.attempt()).isEqualTo(2); });
    }

    @Test
    void requiresBronzeFinalAndReportsUnfinishedHeats() {
        var d = discipline(DisciplineType.PARALLEL_SPRINT); var category = category(d); crew(category, 11L);
        var qualification = mock(Discipline.class); when(qualification.getId()).thenReturn(9L); when(d.getQualificationDiscipline()).thenReturn(qualification);
        var firstResult = mock(kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResult.class);
        var secondResult = mock(kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResult.class);
        var qualified1 = crew(category, 12L); var qualified2 = crew(category, 13L);
        when(firstResult.getCrewEntry()).thenReturn(qualified1); when(secondResult.getCrewEntry()).thenReturn(qualified2);
        for (var result : List.of(firstResult, secondResult)) { when(result.getStatus()).thenReturn(kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus.FINISHED); when(result.getPlace()).thenReturn(1); }
        when(calculated.findByDisciplineIdAndCrewEntryCompetitionCategoryId(9L, 2L)).thenReturn(List.of(firstResult, secondResult));
        var bracket = mock(ParallelSprintBracket.class); when(bracket.getId()).thenReturn(5L);
        when(brackets.findByDisciplineIdAndCategoryId(1L, 2L)).thenReturn(Optional.of(bracket));
        var semi1 = heat(1, 1); var semi2 = heat(1, 2); var finalA = heat(2, 1);
        when(semi1.getCrew1()).thenReturn(mock()); when(semi1.getCrew2()).thenReturn(mock());
        when(semi2.getCrew1()).thenReturn(mock()); when(semi2.getCrew2()).thenReturn(mock());
        when(semi1.getWinnerSlot()).thenReturn(1); when(semi2.getWinnerSlot()).thenReturn(1); when(finalA.getWinnerSlot()).thenReturn(null);
        when(heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(5L)).thenReturn(List.of(semi1, semi2, finalA));
        assertThat(check.discipline(d)).extracting(CompletionCheck.Issue::code).contains("missingBronze", "unfinishedHeat");
        when(disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(44L)).thenReturn(List.of(d));
        assertThat(check.overview(44L).stages()).contains(new CompletionCheck.Stage(1L, 2L, 2, 1, "finalA"));
    }

    @Test
    void reportsIncompleteQualificationAlongsideExistingBracketProblems() {
        var d = discipline(DisciplineType.PARALLEL_SPRINT); var category = category(d); crew(category, 11L);
        var qualification = mock(Discipline.class);
        when(qualification.getId()).thenReturn(9L);
        when(qualification.getType()).thenReturn(DisciplineType.SPRINT);
        when(qualification.getCategories()).thenReturn(java.util.Set.of(category));
        when(d.getQualificationDiscipline()).thenReturn(qualification);
        var bracket = mock(ParallelSprintBracket.class); when(bracket.getId()).thenReturn(5L);
        when(brackets.findByDisciplineIdAndCategoryId(1L, 2L)).thenReturn(Optional.of(bracket));
        var semi1 = heat(1, 1); var semi2 = heat(1, 2); var finalA = heat(2, 1);
        when(semi1.getCrew1()).thenReturn(mock()); when(semi1.getCrew2()).thenReturn(mock());
        when(semi2.getCrew1()).thenReturn(mock()); when(semi2.getCrew2()).thenReturn(mock());
        when(finalA.getWinnerSlot()).thenReturn(null);
        when(heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(5L)).thenReturn(List.of(semi1, semi2, finalA));
        assertThat(check.discipline(d)).extracting(CompletionCheck.Issue::code)
                .contains("qualificationIncomplete", "missingBronze", "unfinishedHeat");
    }
    @Test
    void emptyCompetitionIsNotComplete() {
        when(disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(44L)).thenReturn(List.of());
        assertThat(check.competition(44L)).singleElement().satisfies(issue -> assertThat(issue.code()).isEqualTo("noDisciplines"));
    }

    @Test
    void fewerThanTwoQualifiedCrewsDoNotRequireAnImpossibleBracket() {
        var d = discipline(DisciplineType.PARALLEL_SPRINT); var category = category(d); crew(category, 11L);
        var qualification = mock(Discipline.class); when(qualification.getId()).thenReturn(9L); when(d.getQualificationDiscipline()).thenReturn(qualification);
        when(calculated.findByDisciplineIdAndCrewEntryCompetitionCategoryId(9L, 2L)).thenReturn(List.of());
        assertThat(check.discipline(d)).isEmpty();
    }
    @Test
    void checksQualificationOnlyInTheParallelSprintCategory() {
        var parallel = discipline(DisciplineType.PARALLEL_SPRINT); var a = category(parallel); var crewA = crew(a, 11L);
        var b = mock(CompetitionCategory.class); when(b.getId()).thenReturn(3L);
        var crewB = mock(CrewEntry.class); when(crewB.getId()).thenReturn(12L); when(eligibility.isEligible(crewB)).thenReturn(true);
        when(crews.findByCompetitionCategoryId(3L)).thenReturn(List.of(crewB));
        var qualification = mock(Discipline.class); when(qualification.getId()).thenReturn(9L); when(qualification.getType()).thenReturn(DisciplineType.SPRINT);
        when(qualification.getCategories()).thenReturn(java.util.Set.of(a, b)); when(parallel.getQualificationDiscipline()).thenReturn(qualification);
        var list = mock(StartList.class); when(list.getId()).thenReturn(4L);
        when(lists.findByDisciplineIdAndCategoryId(9L, 2L)).thenReturn(Optional.of(list));
        var slot = mock(StartSlot.class); when(slot.getCrew()).thenReturn(crewA); when(slots.findByStartListIdOrderByPositionAsc(4L)).thenReturn(List.of(slot));
        var result = attempt(crewA, 1, AttemptResultStatus.DNF);
        when(attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(9L, 2L)).thenReturn(List.of(result));
        assertThat(check.discipline(qualification)).extracting(CompletionCheck.Issue::categoryId).contains(3L);
        assertThat(check.discipline(parallel)).isEmpty();
    }    private Discipline discipline(DisciplineType type) {
        var d = mock(Discipline.class); when(d.getId()).thenReturn(1L); when(d.getName()).thenReturn("Sprint"); when(d.getType()).thenReturn(type); return d;
    }
    private CompetitionCategory category(Discipline d) {
        var c = mock(CompetitionCategory.class); when(c.getId()).thenReturn(2L); when(c.getDisplayName()).thenReturn("Open"); when(d.getCategories()).thenReturn(java.util.Set.of(c)); return c;
    }
    private CrewEntry crew(CompetitionCategory category, long id) {
        var c = mock(CrewEntry.class); when(c.getId()).thenReturn(id); when(c.getCrewName()).thenReturn("Crew " + id); when(eligibility.isEligible(c)).thenReturn(true);
        var existing = new java.util.ArrayList<>(crews.findByCompetitionCategoryId(2L)); existing.add(c); when(crews.findByCompetitionCategoryId(2L)).thenReturn(existing); return c;
    }
    private AttemptResult attempt(CrewEntry crew, int number, AttemptResultStatus status) {
        var a = mock(AttemptResult.class); when(a.getCrewEntry()).thenReturn(crew); when(a.getAttemptNumber()).thenReturn(number); when(a.getStatus()).thenReturn(status); return a;
    }
    private ParallelSprintHeat heat(int round, int number) {
        var h = mock(ParallelSprintHeat.class); when(h.getRoundNumber()).thenReturn(round); when(h.getHeatNumber()).thenReturn(number); return h;
    }
}
