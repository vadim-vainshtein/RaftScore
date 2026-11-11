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
