package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
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
class EntryRequirementRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private EntryRequirementRepository requirements;
    @Autowired private CompetitionRepository competitions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void roundTripsAllTypesAndVersionedEditsForCompetition() {
        var competition = competitions.saveAndFlush(competition());
        var types = EntryRequirementValueType.values();
        for (var i = 0; i < types.length; i++) {
            requirements.save(EntryRequirement.builder().competition(competition).name("Requirement")
                    .valueType(types[i]).required(i % 2 == 0).displayOrder(i).build());
        }
        entityManager.flush();
        entityManager.clear();

        var found = requirements.findAll().stream()
                .filter(item -> item.getCompetition().getId().equals(competition.getId()))
                .sorted(java.util.Comparator.comparingInt(EntryRequirement::getDisplayOrder)).toList();
        assertThat(found).hasSize(types.length);
        for (var i = 0; i < types.length; i++) {
            assertThat(found.get(i).getName()).isEqualTo("Requirement");
            assertThat(found.get(i).getValueType()).isEqualTo(types[i]);
            assertThat(found.get(i).isRequired()).isEqualTo(i % 2 == 0);
            assertThat(found.get(i).getDisplayOrder()).isEqualTo(i);
        }
        assertThat(jdbc.queryForList("SELECT value_type FROM entry_requirements WHERE competition_id = ? ORDER BY display_order",
                String.class, competition.getId())).containsExactly("YES_NO", "TEXT", "DATE", "NUMBER");
        var id = found.getFirst().getId();
        found.getFirst().setName("Updated");
        found.getFirst().setRequired(false);
        found.getFirst().setValueType(EntryRequirementValueType.DATE);
        found.getFirst().setDisplayOrder(3);
        entityManager.flush();
        entityManager.clear();
        assertThat(requirements.findById(id)).hasValueSatisfying(updated -> {
            assertThat(updated.getName()).isEqualTo("Updated");
            assertThat(updated.isRequired()).isFalse();
            assertThat(updated.getValueType()).isEqualTo(EntryRequirementValueType.DATE);
            assertThat(updated.getDisplayOrder()).isEqualTo(3);
            assertThat(updated.getVersion()).isOne();
        });
    }

    @Test
    void validatesEntityInputs() {
        var builder = EntryRequirement.builder().competition(competition()).name("Name")
                .valueType(EntryRequirementValueType.TEXT).displayOrder(0);
        assertThatThrownBy(() -> builder.name("  ").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.name("\u001c").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.name("Name").displayOrder(-1).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.displayOrder(0).valueType(null).build()).isInstanceOf(NullPointerException.class);
    }

    @Test
    void databaseEnforcesOwnershipAndFieldConstraints() {
        assertInvalid(null, "Name", "TEXT", 0);
        assertInvalid(-1L, "Name", "TEXT", 0);
        assertInvalidWithOwner("Name", "TEXT", -1);
        assertInvalidWithOwner("Name", "UNKNOWN", 0);
    }

    private void assertInvalidWithOwner(String name, String type, int order) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var owner = competitions.saveAndFlush(competition());
            insert(owner.getId(), name, type, order);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInvalid(Long owner, String name, String type, int order) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            insert(owner, name, type, order);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(Long owner, String name, String type, int order) {
        jdbc.update("INSERT INTO entry_requirements (competition_id, name, value_type, required, display_order) VALUES (?, ?, ?, ?, ?)",
                owner, name, type, true, order);
    }

    private Competition competition() {
        return Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build();
    }
}
