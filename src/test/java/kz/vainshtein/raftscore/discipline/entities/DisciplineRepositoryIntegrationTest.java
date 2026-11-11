package kz.vainshtein.raftscore.discipline.entities;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
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
    @Autowired private CompetitionCategoryRepository categories;
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
    @Transactional
    void replacesCategorySelectionAndKeepsDisciplinesIsolated() {
        var owner = competition();
        var first = category(owner, "First");
        var second = category(owner, "Second");
        var foreign = category(competition(), "Foreign");
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(owner).name("Sprint")
                .type(DisciplineType.SPRINT).build());
        var other = disciplines.saveAndFlush(Discipline.builder().competition(owner).name("Slalom")
                .type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(first, second));
        other.replaceCategories(List.of(first));
        entityManager.flush();
        var version = discipline.getVersion();
        entityManager.clear();

        var loaded = disciplines.findById(discipline.getId()).orElseThrow();
        assertThat(loaded.getCategories()).extracting(CompetitionCategory::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThatThrownBy(() -> loaded.replaceCategories(List.of(second, foreign)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(loaded.getCategories()).extracting(CompetitionCategory::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        loaded.replaceCategories(List.of(second));
        entityManager.flush();
        entityManager.clear();
        var reloaded = disciplines.findById(discipline.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isGreaterThan(version);
        assertThat(reloaded.getCategories()).extracting(CompetitionCategory::getId).containsExactly(second.getId());
        assertThat(disciplines.findById(other.getId()).orElseThrow().getCategories())
                .extracting(CompetitionCategory::getId).containsExactly(first.getId());
        reloaded.replaceCategories(List.of());
        entityManager.flush();
        entityManager.clear();
        assertThat(disciplines.findById(discipline.getId()).orElseThrow().getCategories()).isEmpty();
    }

    private CompetitionCategory category(Competition owner, String label) {
        return categories.saveAndFlush(CompetitionCategory.builder().competition(owner).classLabel(label)
                .scoringGroupLabel("Open").crewSize(4).build());
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
