package kz.vainshtein.raftscore.scoring.entities;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class CalculatedDisciplineResultRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CalculatedDisciplineResultRepository results;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private DisciplineRepository disciplines;
    @Autowired private CrewEntryRepository crews;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void roundTripsAndUpdatesCalculatedResult() {
        var fixture = fixture();
        var result = results.saveAndFlush(result(fixture, 1, 3_000_000_000L, "Best attempt", CalculatedResultStatus.FINISHED, 42));
        var id = result.getId();
        entityManager.clear();
        var loaded = results.findById(id).orElseThrow();
        assertThat(loaded.getDiscipline().getId()).isEqualTo(fixture.discipline().getId());
        assertThat(loaded.getCrewEntry().getId()).isEqualTo(fixture.crew().getId());
        assertThat(loaded.getPlace()).isEqualTo(1);
        assertThat(loaded.getTotalCentiseconds()).isEqualTo(3_000_000_000L);
        assertThat(loaded.getSummary()).isEqualTo("Best attempt");
        assertThat(loaded.getStatus()).isEqualTo(CalculatedResultStatus.FINISHED);
        assertThat(loaded.getPoints()).isEqualTo(42);
        loaded.updateResult(null, null, null, CalculatedResultStatus.DNF, 0);
        entityManager.flush();
        entityManager.clear();
        loaded = results.findById(id).orElseThrow();
        assertThat(loaded.getVersion()).isOne();
        assertThat(loaded.getPlace()).isNull();
        assertThat(loaded.getTotalCentiseconds()).isNull();
        assertThat(loaded.getSummary()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(CalculatedResultStatus.DNF);
        assertThat(loaded.getPoints()).isZero();
        for (var status : CalculatedResultStatus.values()) {
            loaded.updateResult(null, 0L, null, status, 0);
            entityManager.flush();
            entityManager.clear();
            loaded = results.findById(id).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(status);
        }
    }

    @Test
    @Transactional
    void rejectsInvalidValuesWithoutChangingResult() {
        var fixture = fixture();
        var result = result(fixture, 1, 100L, "ok", CalculatedResultStatus.FINISHED, 1);
        assertThatThrownBy(() -> result.updateResult(0, 100L, "ok", CalculatedResultStatus.FINISHED, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.updateResult(1, -1L, "ok", CalculatedResultStatus.FINISHED, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.updateResult(1, 100L, "x".repeat(1001), CalculatedResultStatus.FINISHED, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.updateResult(1, 100L, "\0", CalculatedResultStatus.FINISHED, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.updateResult(1, 100L, "ok", null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.updateResult(1, 100L, "ok", CalculatedResultStatus.FINISHED, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(result.getPlace()).isEqualTo(1);
        assertThat(result.getTotalCentiseconds()).isEqualTo(100L);
        assertThat(result.getSummary()).isEqualTo("ok");
        assertThat(result.getStatus()).isEqualTo(CalculatedResultStatus.FINISHED);
        assertThat(result.getPoints()).isEqualTo(1);
        fixture.discipline().replaceCategories(List.of());
        assertThatThrownBy(() -> result(fixture, 1, 100L, null, CalculatedResultStatus.FINISHED, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesDatabaseConstraints() {
        assertInvalidInsert(0, 0L, "FINISHED", 0, false);
        assertInvalidInsert(1, -1L, "FINISHED", 0, false);
        assertInvalidInsert(1, 0L, "FINISHED", -1, false);
        assertInvalidInsert(1, 0L, "INVALID", 0, false);
        assertInvalidInsert(1, 0L, null, 0, false);
        assertInvalidInsert(1, 0L, "FINISHED", 0, true);
    }

    private void assertInvalidInsert(Integer place, Long time, String status, int points, boolean duplicate) {
        transactions.executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            var fixture = fixture();
            if (duplicate) insert(fixture, place, time, status, points);
            assertThatThrownBy(() -> insert(fixture, place, time, status, points)).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insert(Fixture fixture, Integer place, Long time, String status, int points) {
        jdbc.update("INSERT INTO calculated_discipline_results (discipline_id, crew_entry_id, place, total_centiseconds, status, points) VALUES (?, ?, ?, ?, ?, ?)",
                fixture.discipline().getId(), fixture.crew().getId(), place, time, status, points);
    }

    private CalculatedDisciplineResult result(Fixture fixture, Integer place, Long time, String summary, CalculatedResultStatus status, int points) {
        return CalculatedDisciplineResult.builder().discipline(fixture.discipline()).crewEntry(fixture.crew())
                .place(place).totalCentiseconds(time).summary(summary).status(status).points(points).build();
    }

    private Fixture fixture() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint")
                .type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category));
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew")
                .regionOrClub("Club").representative("Person").startNumber(1).build());
        return new Fixture(discipline, crew);
    }

    private record Fixture(Discipline discipline, CrewEntry crew) { }
}
