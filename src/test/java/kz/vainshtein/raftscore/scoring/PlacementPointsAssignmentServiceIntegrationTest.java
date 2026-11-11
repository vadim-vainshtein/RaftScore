package kz.vainshtein.raftscore.scoring;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlacementPointsAssignmentServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private PlacementPointsAssignmentService assignment;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private DisciplineRepository disciplines;
    @Autowired private CrewEntryRepository crews;
    @Autowired private PlacementPointsTableRepository tables;
    @Autowired private CalculatedDisciplineResultRepository results;
    @Autowired private EntityManager entityManager;
    @Autowired private kz.vainshtein.raftscore.standings.OverallStandingsService standings;

    @Test
    @Transactional
    void assignsCurrentTableOnlyToRequestedDisciplineAndCategory() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = category(competition, "Open");
        var otherCategory = category(competition, "Women");
        var discipline = discipline(competition, category, otherCategory);
        var otherDiscipline = discipline(competition, category, otherCategory);
        var table = tables.saveAndFlush(new PlacementPointsTable(competition, "Points",
                TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(1, 100), new PlacementPointsRow(2, 50))));
        discipline.setPointsTable(table);
        var first = result(discipline, category, 1, 1, CalculatedResultStatus.FINISHED);
        var tied = result(discipline, category, 2, 1, CalculatedResultStatus.FINISHED);
        var missing = result(discipline, category, 3, 3, CalculatedResultStatus.FINISHED);
        var noPlace = result(discipline, category, 4, null, CalculatedResultStatus.FINISHED);
        var dnf = result(discipline, category, 5, 1, CalculatedResultStatus.DNF);
        var outsideCategory = result(discipline, otherCategory, 6, 1, CalculatedResultStatus.FINISHED);
        var outsideDiscipline = result(otherDiscipline, category, 7, 1, CalculatedResultStatus.FINISHED);
        entityManager.flush();
        entityManager.clear();

        assignment.assign(discipline.getId(), category.getId());
        entityManager.flush();
        entityManager.clear();
        assertPoints(first, 100);
        assertPoints(tied, 100);
        assertPoints(missing, 0);
        assertPoints(noPlace, 0);
        assertPoints(dnf, 0);
        assertPoints(outsideCategory, 9);
        assertPoints(outsideDiscipline, 9);

        var currentTable = tables.findById(table.getId()).orElseThrow();
        currentTable.replace("Updated", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(1, 35)));
        entityManager.flush();
        entityManager.clear();
        assignment.assign(discipline.getId(), category.getId());
        entityManager.flush();
        entityManager.clear();
        assertPoints(first, 35);
        assertPoints(tied, 35);

        disciplines.findById(discipline.getId()).orElseThrow().setPointsTable(null);
        entityManager.flush();
        entityManager.clear();
        assignment.assign(discipline.getId(), category.getId());
        entityManager.flush();
        entityManager.clear();
        assertPoints(first, 0);
        assertPoints(tied, 0);
    }

    @Test
    @Transactional
    void standingsUseCurrentAssignedResultsAndSharedPlaces() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = category(competition, "Open");
        var otherCategory = category(competition, "Women");
        var sprint = discipline(competition, category, otherCategory);
        var otherDiscipline = discipline(competition, category);
        var first = result(sprint, category, 1, 1, CalculatedResultStatus.FINISHED);
        var tied = result(sprint, category, 2, 1, CalculatedResultStatus.FINISHED);
        var third = result(sprint, category, 3, 3, CalculatedResultStatus.FINISHED);
        result(sprint, category, 4, null, CalculatedResultStatus.DSQ_COMPETITION);
        result(sprint, otherCategory, 5, 1, CalculatedResultStatus.FINISHED);
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(otherDiscipline)
                .crewEntry(third.getCrewEntry()).place(1).status(CalculatedResultStatus.FINISHED).points(10).build());
        var table = tables.saveAndFlush(new PlacementPointsTable(competition, "Points",
                TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(1, 100), new PlacementPointsRow(3, 50))));
        sprint.setPointsTable(table);
        entityManager.flush(); entityManager.clear();
        assignment.assign(sprint.getId(), category.getId());
        entityManager.flush(); entityManager.clear();
        var current = standings.standings(category.getId());
        assertThat(current).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::totalPoints)
                .containsExactly(100L, 100L, 60L, 0L);
        assertThat(current).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::place)
                .containsExactly(1, 1, 3, null);
        assertThat(current.get(2).disciplines()).hasSize(2);
        assertThat(current.get(3).disqualified()).isTrue();
        disciplines.findById(otherDiscipline.getId()).orElseThrow().replaceCategories(List.of());
        entityManager.flush(); entityManager.clear();
        assertThat(standings.standings(category.getId()).get(2).totalPoints()).isEqualTo(50);
        var currentTable = tables.findById(table.getId()).orElseThrow();
        currentTable.replace("Updated", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(1, 35)));
        entityManager.flush(); entityManager.clear();
        assignment.assign(sprint.getId(), category.getId());
        entityManager.flush(); entityManager.clear();
        assertThat(standings.standings(category.getId())).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::totalPoints)
                .containsExactly(35L, 35L, 0L, 0L);
    }
    @Test
    @Transactional
    void tiebreakRequiresResultsForTheWholeEqualTotalGroup() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = category(competition, "Open");
        var sprint = discipline(competition, category);
        var selected = discipline(competition, category);
        var first = result(sprint, category, 1, 1, CalculatedResultStatus.FINISHED);
        var missing = result(sprint, category, 2, 1, CalculatedResultStatus.FINISHED);
        var third = result(sprint, category, 3, 1, CalculatedResultStatus.FINISHED);
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(selected).crewEntry(first.getCrewEntry())
                .place(2).status(CalculatedResultStatus.FINISHED).points(0).build());
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(selected).crewEntry(third.getCrewEntry())
                .place(1).status(CalculatedResultStatus.FINISHED).points(0).build());
        competition.setTiebreakDiscipline(selected);
        entityManager.flush(); entityManager.clear();
        var unresolved = standings.standings(category.getId());
        assertThat(unresolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::appliedTiebreakDisciplineId).containsOnlyNulls();
        assertThat(unresolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::place)
                .containsExactly(1, 1, 1);
        assertThat(unresolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::crewId)
                .containsExactly(first.getCrewEntry().getId(), missing.getCrewEntry().getId(), third.getCrewEntry().getId());
        results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(disciplines.findById(selected.getId()).orElseThrow())
                .crewEntry(crews.findById(missing.getCrewEntry().getId()).orElseThrow())
                .place(2).status(CalculatedResultStatus.FINISHED).points(0).build());
        entityManager.flush(); entityManager.clear();
        var resolved = standings.standings(category.getId());
        assertThat(resolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::appliedTiebreakDisciplineId).containsOnly(selected.getId());
        assertThat(resolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::crewId)
                .containsExactly(third.getCrewEntry().getId(), first.getCrewEntry().getId(), missing.getCrewEntry().getId());
        assertThat(resolved).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::place)
                .containsExactly(1, 2, 2);
        for (var row : results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(selected.getId(), category.getId()))
            row.updateResult(1, null, null, CalculatedResultStatus.FINISHED, 0);
        entityManager.flush(); entityManager.clear();
        assertThat(standings.standings(category.getId())).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::appliedTiebreakDisciplineId).containsOnlyNulls();
        disciplines.findById(selected.getId()).orElseThrow().replaceCategories(List.of());
        entityManager.flush(); entityManager.clear();
        assertThat(standings.standings(category.getId())).extracting(kz.vainshtein.raftscore.standings.OverallStandingsService.Standing::place)
                .containsExactly(1, 1, 1);
    }

    private CompetitionCategory category(Competition competition, String label) {
        return categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel(label).scoringGroupLabel(label).crewSize(4).build());
    }

    private Discipline discipline(Competition competition, CompetitionCategory... selected) {
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint")
                .type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(selected));
        return discipline;
    }

    private CalculatedDisciplineResult result(Discipline discipline, CompetitionCategory category, int number,
                                               Integer place, CalculatedResultStatus status) {
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number)
                .regionOrClub("Club").representative("Person").startNumber(number).build());
        return results.saveAndFlush(CalculatedDisciplineResult.builder().discipline(discipline).crewEntry(crew)
                .place(place).status(status).points(9).build());
    }

    private void assertPoints(CalculatedDisciplineResult result, int points) {
        assertThat(results.findById(result.getId()).orElseThrow().getPoints()).isEqualTo(points);
    }
}
