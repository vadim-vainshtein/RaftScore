package kz.vainshtein.raftscore.catalog.entities;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CategoryTemplateRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CategoryTemplateRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void persistsAndUpdatesTemplateWithOptimisticVersion() {
        var template = repository.saveAndFlush(CategoryTemplate.builder()
                .classLabel("R6").scoringGroupLabel("W U.24").crewSize(6).build());
        template.setArchived(true);
        template.setCrewSize(4);
        entityManager.flush();
        entityManager.clear();
        assertThat(repository.findById(template.getId())).hasValueSatisfying(found -> {
            assertThat(found.getDisplayName()).isEqualTo("R6 / W U.24");
            assertThat(found.getCrewSize()).isEqualTo(4);
            assertThat(found.isArchived()).isTrue();
            assertThat(found.getVersion()).isOne();
        });
    }

    @Test
    void databaseRejectsNonPositiveCrewSize() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO category_templates (class_label, scoring_group_label, crew_size, archived) VALUES (?, ?, ?, ?)",
                "R4", "Men Open", 0, false)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
