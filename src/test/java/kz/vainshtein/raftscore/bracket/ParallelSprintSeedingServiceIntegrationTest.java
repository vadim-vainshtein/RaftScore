package kz.vainshtein.raftscore.bracket;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ParallelSprintSeedingServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired ParallelSprintSeedingService service;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired EntityManager entityManager;

    @Test
    void seedsTiesAndByesWithoutAdvancingUnresolvedHeats() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var other = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Other").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n : List.of(5, 4, 3, 2, 1)) {
            var crew = crew(category, n);
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew).place(n <= 2 ? 1 : n).status(CalculatedResultStatus.FINISHED).build());
        }
        var excluded = crew(category, 6);
        results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(excluded).status(CalculatedResultStatus.DNF).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        entityManager.flush();
        entityManager.clear();
        var generated = heats.findAll().stream().filter(h -> h.getBracket().getId().equals(bracket.getId())).toList();
        assertThat(generated).hasSize(7);
        var first = generated.stream().filter(h -> h.getRoundNumber() == 1).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(first).extracting(h -> h.getCrew1().getStartNumber()).containsExactly(1, 4, 2, 3);
        assertThat(first).extracting(h -> h.getCrew2() == null ? null : h.getCrew2().getStartNumber()).containsExactly(null, 5, null, null);
        var second = generated.stream().filter(h -> h.getRoundNumber() == 2).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(second).extracting(h -> h.getCrew1().getStartNumber()).containsExactly(1, 2);
        assertThat(second).extracting(h -> h.getCrew2() == null ? null : h.getCrew2().getStartNumber()).containsExactly(null, 3);
        assertThat(generated.stream().filter(h -> h.getRoundNumber() == 3).findFirst().orElseThrow().getCrew1()).isNull();
        assertThat(first).allSatisfy(h -> assertThat(h.getNextHeat()).isNotNull());
        assertThatThrownBy(() -> service.seed(parallel.getId(), other.getId(), sprint.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.seed(parallel.getId(), category.getId(), sprint.getId())).isInstanceOf(IllegalStateException.class);
    }

    private Discipline discipline(Competition competition, CompetitionCategory category, DisciplineType type) {
        var d = disciplines.save(Discipline.builder().competition(competition).name(type.name()).type(type).build());
        d.replaceCategories(List.of(category));
        disciplines.flush();
        return d;
    }
    private CrewEntry crew(CompetitionCategory category, int n) {
        return crews.save(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).regionOrClub("Club").representative("Person").startNumber(n).build());
    }
}
