package kz.vainshtein.raftscore.slalom.entities;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.scoring.SlalomRankingService;
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
class SlalomConfigurationIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired AttemptResultRepository attempts;
    @Autowired SlalomConfigurationRepository configurations;
    @Autowired SlalomGatePenaltyRepository penalties;
    @Autowired StartListRepository startLists;
    @Autowired StartSlotRepository startSlots;
    @Autowired SlalomRankingService ranking;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test @Transactional
    void persistsOrderedGatesDefaultAttemptsAndPenalties() {
        var fixture = fixture();
        var config = configurations.saveAndFlush(new SlalomConfiguration(fixture.discipline(), 3));
        var attempt = attempts.saveAndFlush(fixture.attempt());
        for (int i = 0; i < 3; i++) penalties.save(new SlalomGatePenalty(attempt, config, i + 1, new int[]{0, 5, 50}[i]));
        em.flush();
        var id = config.getId();
        em.clear();
        var loaded = configurations.findById(id).orElseThrow();
        assertThat(loaded.getAttemptCount()).isEqualTo(2);
        assertThat(loaded.getGateCount()).isEqualTo(3);
        assertThat(penalties.findAll()).extracting(SlalomGatePenalty::getGateNumber).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(penalties.findAll()).extracting(SlalomGatePenalty::getPenaltySeconds).containsExactlyInAnyOrder(0, 5, 50);
        loaded.setAttemptCount(3);
        em.flush();
        em.clear();
        assertThat(configurations.findById(id).orElseThrow().getAttemptCount()).isEqualTo(3);
    }

    @Test @Transactional
    void validatesCountsDisciplineOwnershipAndPenalties() {
        var fixture = fixture();
        assertThatThrownBy(() -> new SlalomConfiguration(fixture.discipline(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlalomConfiguration(fixture.discipline(), 1, 0)).isInstanceOf(IllegalArgumentException.class);
        var sprint = Discipline.builder().competition(fixture.discipline().getCompetition()).name("Sprint").type(DisciplineType.SPRINT).build();
        assertThatThrownBy(() -> new SlalomConfiguration(sprint, 1)).isInstanceOf(IllegalArgumentException.class);
        var config = new SlalomConfiguration(fixture.discipline(), 1);
        assertThatThrownBy(() -> new SlalomGatePenalty(fixture.attempt(), config, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        var other = fixture();
        assertThatThrownBy(() -> new SlalomGatePenalty(other.attempt(), config, 1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.setAttemptCount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(config.getAttemptCount()).isEqualTo(2);
        assertThat(new SlalomConfiguration(fixture.discipline(), Integer.MAX_VALUE).getGateCount()).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> new SlalomGatePenalty(fixture.attempt(), config, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseRejectsDuplicateGatesAndInvalidValues() {
        assertDatabaseFailure("INSERT INTO slalom_gate_penalties (attempt_result_id, configuration_id, gate_number, penalty_seconds) VALUES (?, ?, 1, 0)", false);
        assertDatabaseFailure("INSERT INTO slalom_gate_penalties (attempt_result_id, configuration_id, gate_number, penalty_seconds) VALUES (?, ?, 1, 7)", true);
    }

    @Test
    void databaseRejectsMismatchedDisciplineAndOutOfRangeGateOnInsertAndUpdate() {
        for (var update : List.of(false, true)) {
            for (var mismatch : List.of(false, true)) {
                transactions.executeWithoutResult(tx -> {
                    tx.setRollbackOnly();
                    var fixture = fixture();
                    var other = fixture();
                    var config = configurations.saveAndFlush(new SlalomConfiguration(fixture.discipline(), 1));
                    var attempt = attempts.saveAndFlush(fixture.attempt());
                    var otherAttempt = attempts.saveAndFlush(other.attempt());
                    var attemptId = mismatch ? otherAttempt.getId() : attempt.getId();
                    var gateNumber = mismatch ? 1 : 2;
                    if (update) {
                        var id = jdbc.queryForObject("INSERT INTO slalom_gate_penalties (attempt_result_id, configuration_id, gate_number, penalty_seconds) VALUES (?, ?, 1, 0) RETURNING id", Long.class, attempt.getId(), config.getId());
                        assertThatThrownBy(() -> jdbc.update("UPDATE slalom_gate_penalties SET attempt_result_id = ?, gate_number = ? WHERE id = ?", attemptId, gateNumber, id))
                                .isInstanceOf(DataIntegrityViolationException.class);
                    } else {
                        assertThatThrownBy(() -> jdbc.update("INSERT INTO slalom_gate_penalties (attempt_result_id, configuration_id, gate_number, penalty_seconds) VALUES (?, ?, ?, 0)", attemptId, config.getId(), gateNumber))
                                .isInstanceOf(DataIntegrityViolationException.class);
                    }
                });
            }
        }
    }
    private void assertDatabaseFailure(String sql, boolean penalty) {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var fixture = fixture();
            var config = configurations.saveAndFlush(new SlalomConfiguration(fixture.discipline(), 1));
            var attempt = attempts.saveAndFlush(fixture.attempt());
            if (!penalty) jdbc.update(sql, attempt.getId(), config.getId());
            assertThatThrownBy(() -> jdbc.update(sql, attempt.getId(), config.getId())).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    @Test @Transactional
    void ranksPersistedInputsAfterClearingPersistenceContext() {
        var fixture = fixture();
        var discipline = fixture.discipline();
        var crew = fixture.attempt().getCrewEntry();
        var list = startLists.saveAndFlush(StartList.builder().discipline(discipline).category(crew.getCompetitionCategory()).build());
        startSlots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(1).build());
        var config = configurations.saveAndFlush(new SlalomConfiguration(discipline, 1));
        var attempt = attempts.saveAndFlush(AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1)
                .durationCentiseconds(1000).status(AttemptResultStatus.FINISHED).build());
        penalties.saveAndFlush(new SlalomGatePenalty(attempt, config, 1, 5));
        var listId = list.getId();
        em.flush();
        em.clear();
        var result = ranking.rank(listId);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().totalCentiseconds()).isEqualTo(1500L);
        assertThat(result.getFirst().place()).isEqualTo(1);
    }
    private Fixture fixture() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category));
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew").regionOrClub("Club").representative("Person").startNumber(1).build());
        var attempt = AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(1).status(AttemptResultStatus.DNS).build();
        return new Fixture(discipline, attempt);
    }
    private record Fixture(Discipline discipline, AttemptResult attempt) { }
}
