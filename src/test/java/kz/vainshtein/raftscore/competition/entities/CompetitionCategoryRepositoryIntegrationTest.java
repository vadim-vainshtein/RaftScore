package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplate;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
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
class CompetitionCategoryRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CategoryTemplateRepository templates;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @Transactional
    void persistsIndependentCategoryAndVersionedEdits() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("Europe/London").build());
        var template = templates.saveAndFlush(CategoryTemplate.builder()
                .classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel(template.getClassLabel()).scoringGroupLabel(template.getScoringGroupLabel())
                .crewSize(template.getCrewSize()).build());
        template.setClassLabel("C1");
        template.setScoringGroupLabel("Women");
        template.setCrewSize(1);
        entityManager.flush();
        entityManager.clear();

        var found = categories.findById(category.getId()).orElseThrow();
        assertThat(found.getCompetition().getId()).isEqualTo(competition.getId());
        assertThat(found.getDisplayName()).isEqualTo("R6 / Open");
        assertThat(found.getCrewSize()).isEqualTo(6);
        found.setCrewSize(4);
        entityManager.flush();
        entityManager.clear();
        assertThat(categories.findById(category.getId())).hasValueSatisfying(updated -> {
            assertThat(updated.getCrewSize()).isEqualTo(4);
            assertThat(updated.getVersion()).isOne();
        });
        assertThat(templates.findById(template.getId())).hasValueSatisfying(updated ->
                assertThat(updated.getCrewSize()).isOne());
    }

    @Test
    void databaseRequiresExistingOwnerAndPositiveCrewSize() {
        assertThatThrownBy(() -> insertCategory(null, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCategory(-1L, 4)).isInstanceOf(DataIntegrityViolationException.class);
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                    .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("Europe/London").build());
            assertThatThrownBy(() -> insertCategory(competition.getId(), 0))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test
    void databaseEnforcesUniqueCombinationPerCompetition() {
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var first = competitions.saveAndFlush(Competition.builder().title("First")
                    .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
            var second = competitions.saveAndFlush(Competition.builder().title("Second")
                    .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
            insertCategory(first.getId(), 4);
            insertCategory(second.getId(), 4);
            assertThatThrownBy(() -> insertCategory(first.getId(), 6))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("competition_categories_combination_uk");
        });
    }
    @Test
    void databaseAcceptsLongDistinctLabelsAndRejectsExactDuplicates() {
        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var competition = competitions.saveAndFlush(Competition.builder().title("Long labels")
                    .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
            var label = java.util.stream.IntStream.range(0, 300).mapToObj(i -> java.util.UUID.randomUUID().toString())
                    .collect(java.util.stream.Collectors.joining());
            var sql = "INSERT INTO competition_categories (competition_id, class_label, scoring_group_label, crew_size) VALUES (?, ?, ?, 4)";
            jdbcTemplate.update(sql, competition.getId(), label, label);
            jdbcTemplate.update(sql, competition.getId(), label + "different", label);
            assertThatThrownBy(() -> jdbcTemplate.update(sql, competition.getId(), label, label))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("competition_categories_combination_uk");
        });
    }
    private void insertCategory(Long competitionId, int crewSize) {
        jdbcTemplate.update("INSERT INTO competition_categories (competition_id, class_label, scoring_group_label, crew_size) VALUES (?, ?, ?, ?)",
                competitionId, "R4", "Open", crewSize);
    }
}
