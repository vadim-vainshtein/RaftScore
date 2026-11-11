package kz.vainshtein.raftscore.entry.entities;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
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
class CrewRequirementValueRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CrewRequirementValueRepository values;
    @Autowired private CrewEntryRepository crews;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private EntryRequirementRepository requirements;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void roundTripsAndVersionsAllTypes() {
        var competition = competition();
        var crew = crew(competition);
        var answers = new Object[] { false, "Answer", LocalDate.of(2026, 7, 1), new BigDecimal("12345678901234567890.12345678901234567890") };
        var types = EntryRequirementValueType.values();
        Long booleanId = null;
        for (int i = 0; i < types.length; i++) {
            var requirement = requirement(competition, types[i]);
            var answer = answers[i];
            var saved = values.saveAndFlush(CrewRequirementValue.builder().crewEntry(crew).requirement(requirement).value(answer).build());
            if (types[i] == EntryRequirementValueType.YES_NO) booleanId = saved.getId();
            entityManager.clear();
            var loaded = values.findById(saved.getId()).orElseThrow();
            var actual = switch (types[i]) {
                case YES_NO -> loaded.getBooleanValue();
                case TEXT -> loaded.getTextValue();
                case DATE -> loaded.getDateValue();
                case NUMBER -> loaded.getNumericValue();
            };
            assertThat(actual).isEqualTo(answer);
            loaded.setValue(answer);
        }
        var entry = values.findById(booleanId).orElseThrow();
        entry.setValue(true);
        entityManager.flush();
        assertThat(entry.getVersion()).isOne();
    }

    @Test
    @Transactional
    void rejectsWrongTypeDifferentCompetitionAndAbsentValue() {
        var competition = competition();
        var crew = crew(competition);
        var requirement = requirement(competition, EntryRequirementValueType.TEXT);
        assertThatThrownBy(() -> value(crew, requirement, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value(crew, requirement, null)).isInstanceOf(NullPointerException.class);
        var other = requirement(competition(), EntryRequirementValueType.TEXT);
        assertThatThrownBy(() -> value(crew, other, "answer")).isInstanceOf(IllegalArgumentException.class);
        var number = requirement(competition, EntryRequirementValueType.NUMBER);
        assertThatThrownBy(() -> value(crew, number, BigDecimal.ONE.scaleByPowerOfTen(131072)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseRejectsDuplicateAndInvalidColumnShapes() {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var competition = competition();
            var crew = crew(competition);
            var requirement = requirement(competition, EntryRequirementValueType.TEXT);
            values.saveAndFlush(value(crew, requirement, "answer"));
            assertThatThrownBy(() -> values.saveAndFlush(value(crew, requirement, "another")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var competition = competition();
            var crew = crew(competition);
            var requirement = requirement(competition, EntryRequirementValueType.TEXT);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO crew_requirement_values (crew_entry_id, requirement_id, value_type, boolean_value) VALUES (?, ?, 'TEXT', true)", crew.getId(), requirement.getId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test
    @Transactional
    void requirementTypeCannotChangeWhileAnsweredButUnansweredTypeCanChange() {
        var competition = competition();
        var crew = crew(competition);
        var answered = requirement(competition, EntryRequirementValueType.TEXT);
        var unanswered = requirement(competition, EntryRequirementValueType.TEXT);
        var answer = values.saveAndFlush(value(crew, answered, "preserved"));
        unanswered.setValueType(EntryRequirementValueType.DATE);
        entityManager.flush();
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("UPDATE entry_requirements SET value_type = 'NUMBER' WHERE id = ?")) {
                statement.setLong(1, answered.getId());
                assertThatThrownBy(statement::executeUpdate).isInstanceOf(java.sql.SQLException.class);
            } finally {
                connection.rollback(savepoint);
            }
            return null;
        });
        entityManager.clear();
        assertThat(requirements.findById(answered.getId()).orElseThrow().getValueType()).isEqualTo(EntryRequirementValueType.TEXT);
        assertThat(requirements.findById(unanswered.getId()).orElseThrow().getValueType()).isEqualTo(EntryRequirementValueType.DATE);
        assertThat(values.findById(answer.getId()).orElseThrow().getTextValue()).isEqualTo("preserved");
    }

    private CrewRequirementValue value(CrewEntry crew, EntryRequirement requirement, Object value) {
        return CrewRequirementValue.builder().crewEntry(crew).requirement(requirement).value(value).build();
    }
    private Competition competition() {
        return competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build());
    }
    private CrewEntry crew(Competition competition) {
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        return crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew")
                .regionOrClub("Club").representative("Coach").startNumber(1).build());
    }
    private EntryRequirement requirement(Competition competition, EntryRequirementValueType type) {
        return requirements.saveAndFlush(EntryRequirement.builder().competition(competition).name("Requirement")
                .valueType(type).displayOrder(0).build());
    }
}
