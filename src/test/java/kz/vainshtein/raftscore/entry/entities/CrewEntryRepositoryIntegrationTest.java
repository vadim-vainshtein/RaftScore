package kz.vainshtein.raftscore.entry.entities;

import java.time.LocalDate;

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
class CrewEntryRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private CrewEntryRepository entries;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void persistsAndVersionsEditableDetails() {
        var category = createCategory();
        var entry = entries.saveAndFlush(CrewEntry.builder().competitionCategory(category)
                .crewName("Rapids").regionOrClub("River Club").representative("Coach")
                .startNumber(42).build());
        entityManager.clear();

        var found = entries.findById(entry.getId()).orElseThrow();
        assertThat(found.getCompetitionCategory().getId()).isEqualTo(category.getId());
        assertThat(found.getCompetitionCategory().getCompetition().getId()).isEqualTo(category.getCompetition().getId());
        assertThat(found.getCrewName()).isEqualTo("Rapids");
        assertThat(found.getRegionOrClub()).isEqualTo("River Club");
        assertThat(found.getRepresentative()).isEqualTo("Coach");
        assertThat(found.getStartNumber()).isEqualTo(42);
        found.updateDetails("Waves", "North", "Manager", 7);
        entityManager.flush();
        entityManager.clear();
        assertThat(entries.findById(entry.getId())).hasValueSatisfying(updated -> {
            assertThat(updated.getCrewName()).isEqualTo("Waves");
            assertThat(updated.getRegionOrClub()).isEqualTo("North");
            assertThat(updated.getRepresentative()).isEqualTo("Manager");
            assertThat(updated.getStartNumber()).isEqualTo(7);
            assertThat(updated.getVersion()).isOne();
        });
    }

    @Test
    void databaseRequiresCategoryAndPositiveStartNumber() {
        assertInvalidInsert(null, 1);
        assertInvalidInsert(-1L, 1);
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var category = createCategory();
            assertThatThrownBy(() -> insert(category.getId(), 0)).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test
    void databaseEnforcesStartNumberUniquenessPerCategory() {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var first = createCategory();
            var second = createCategory();
            insert(first.getId(), 42);
            insert(second.getId(), 42);
            assertThatThrownBy(() -> insert(first.getId(), 42))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasStackTraceContaining("crew_entries_category_start_number_uk");
        });
    }
    @Test
    void rejectsInvalidDetailsBeforeChangingAnEntry() {
        var entry = CrewEntry.builder().competitionCategory(CompetitionCategory.builder()
                .competition(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                        .location("River").timeZone("Europe/London").build())
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build())
                .crewName("Rapids").regionOrClub("Club").representative("Coach").startNumber(1).build();
        assertThatThrownBy(() -> entry.updateDetails("Changed", "Club", "Coach", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.updateDetails(" ", "Club", "Coach", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entry.getCrewName()).isEqualTo("Rapids");
        assertThat(entry.getStartNumber()).isOne();
    }

    private void assertInvalidInsert(Long categoryId, int startNumber) {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> insert(categoryId, startNumber)).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insert(Long categoryId, int startNumber) {
        jdbc.update("INSERT INTO crew_entries (competition_category_id, crew_name, region_or_club, representative, start_number) VALUES (?, ?, ?, ?, ?)",
                categoryId, "Crew", "Club", "Coach", startNumber);
    }

    private CompetitionCategory createCategory() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("Europe/London").build());
        return categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
    }
}
