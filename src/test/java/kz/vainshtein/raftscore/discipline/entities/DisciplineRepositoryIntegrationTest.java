package kz.vainshtein.raftscore.discipline.entities;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DisciplineRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private DisciplineRepository disciplines;
    @Autowired private CompetitionRepository competitions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void persistsAllTypesDuplicatesAndVersionedEdits() {
        var competition = competition();
        for (var type : DisciplineType.values()) {
            disciplines.save(Discipline.builder().competition(competition).name(type.name())
                    .type(type).displayOrder(type.ordinal()).build());
        }
        var duplicate = disciplines.saveAndFlush(Discipline.builder().competition(competition)
                .name("Second sprint").type(DisciplineType.SPRINT).displayOrder(0).build());
        entityManager.clear();
        var found = disciplines.findById(duplicate.getId()).orElseThrow();
        assertThat(found.getCompetition().getId()).isEqualTo(competition.getId());
        assertThat(found.getType()).isEqualTo(DisciplineType.SPRINT);
        assertThat(jdbc.queryForObject("SELECT type FROM disciplines WHERE id = ?", String.class, found.getId()))
                .isEqualTo("SPRINT");
        found.setName("Updated");
        found.setDisplayOrder(5);
        entityManager.flush();
        entityManager.clear();
        assertThat(disciplines.findById(duplicate.getId())).hasValueSatisfying(updated -> {
            assertThat(updated.getName()).isEqualTo("Updated");
            assertThat(updated.getDisplayOrder()).isEqualTo(5);
            assertThat(updated.getVersion()).isOne();
        });
        assertThat(disciplines.findAll()).extracting(Discipline::getType)
                .contains(DisciplineType.values());
    }

    @Test
    void rejectsInvalidValuesInEntityAndDatabase() {
        assertThatThrownBy(() -> Discipline.builder().competition(newCompetition()).name(" ")
                .type(DisciplineType.SPRINT).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discipline.builder().competition(newCompetition()).name("Sprint")
                .type(DisciplineType.SPRINT).displayOrder(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Discipline.builder().competition(newCompetition()).name("Sprint")
                .type(null).build()).isInstanceOf(NullPointerException.class);
        assertInvalidInsert(null, "Sprint", "SPRINT", 0);
        assertInvalidInsert(-1L, "Sprint", "SPRINT", 0);
        assertInvalidWithOwner(" ", "SPRINT", 0);
        assertInvalidWithOwner("Sprint", "OTHER", 0);
        assertInvalidWithOwner("Sprint", "SPRINT", -1);
    }

    private void assertInvalidWithOwner(String name, String type, int order) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var owner = competition().getId();
            assertThatThrownBy(() -> insert(owner, name, type, order))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void assertInvalidInsert(Long owner, String name, String type, int order) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> insert(owner, name, type, order))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insert(Long owner, String name, String type, int order) {
        jdbc.update("INSERT INTO disciplines (competition_id, name, type, display_order) VALUES (?, ?, ?, ?)",
                owner, name, type, order);
    }

    private Competition competition() {
        return competitions.saveAndFlush(newCompetition());
    }

    private Competition newCompetition() {
        return Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build();
    }
}
