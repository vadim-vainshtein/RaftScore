package kz.vainshtein.raftscore.scoring.entities;

import java.time.LocalDate;
import java.util.List;

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
class PlacementPointsTableRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private PlacementPointsTableRepository tables;
    @Autowired private CompetitionRepository competitions;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void roundTripsOrderedRowsZeroPolicyAndVersionedReplacement() {
        var owner = competitions.saveAndFlush(competition());
        var table = tables.saveAndFlush(new PlacementPointsTable(owner, "Schedule", TiePointsPolicy.SHARED_PLACE_SAME_POINTS,
                List.of(new PlacementPointsRow(3, 0), new PlacementPointsRow(1, 100))));
        var id = table.getId();
        entityManager.clear();
        var loaded = tables.findById(id).orElseThrow();
        assertThat(loaded.getCompetition().getId()).isEqualTo(owner.getId());
        assertThat(loaded.getName()).isEqualTo("Schedule");
        assertThat(loaded.getTiePolicy()).isEqualTo(TiePointsPolicy.SHARED_PLACE_SAME_POINTS);
        assertThat(loaded.getRows()).extracting(PlacementPointsRow::getPlace).containsExactly(1, 3);
        assertThat(loaded.getRows()).extracting(PlacementPointsRow::getPoints).containsExactly(100, 0);
        assertThatThrownBy(() -> loaded.replace("Invalid", TiePointsPolicy.SHARED_PLACE_SAME_POINTS,
                List.of(new PlacementPointsRow(1, 1), new PlacementPointsRow(1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(loaded.getName()).isEqualTo("Schedule");
        assertThat(loaded.getRows()).hasSize(2);
        loaded.replace("Schedule", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of(new PlacementPointsRow(2, 0)));
        entityManager.flush();
        entityManager.clear();
        var updated = tables.findById(id).orElseThrow();
        assertThat(updated.getVersion()).isOne();
        assertThat(updated.getName()).isEqualTo("Schedule");
        assertThat(updated.getRows()).extracting(PlacementPointsRow::getPlace).containsExactly(2);
    }

    @Test
    void rejectsInvalidValuesAndDatabaseConstraints() {
        assertThatThrownBy(() -> new PlacementPointsRow(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlacementPointsRow(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertInvalid("INSERT INTO placement_points_tables (competition_id, tie_policy) VALUES (?, ?)", -1L, "SHARED_PLACE_SAME_POINTS");
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var owner = competitions.saveAndFlush(competition());
            assertThatThrownBy(() -> new PlacementPointsTable(owner, " ", TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        });
        assertInvalidWithTable("INSERT INTO placement_points_rows (table_id, place, points) VALUES (?, 0, 0)");
        assertInvalidWithTable("INSERT INTO placement_points_rows (table_id, place, points) VALUES (?, 1, -1)");
        assertInvalidWithTable("INSERT INTO placement_points_rows (table_id, place, points) VALUES (?, 1, 0), (?, 1, 2)");
        assertInvalidWithTable("UPDATE placement_points_tables SET tie_policy = 'UNKNOWN' WHERE id = ?");
    }

    private void assertInvalid(String sql, Object... args) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update(sql, args);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInvalidWithTable(String sql) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            var owner = competitions.saveAndFlush(competition());
            var table = tables.saveAndFlush(new PlacementPointsTable(owner, null,
                    TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of()));
            if (sql.contains("), (?")) {
                jdbc.update(sql, table.getId(), table.getId());
            } else {
                jdbc.update(sql, table.getId());
            }
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Competition competition() {
        return Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1))
                .location("River").timeZone("Europe/London").build();
    }
}
