package kz.vainshtein.raftscore.bracket.entities;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
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
class ParallelSprintBracketRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired ParallelSprintBracketRepository brackets;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @Test @Transactional
    void roundTripsCompetitorsOutcomesAndProgression() {
        var f = fixture();
        var target = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(f.bracket()).roundNumber(2).heatNumber(1).build());
        var source = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(f.bracket()).roundNumber(1).heatNumber(1)
                .crew1(f.crew1()).crew2(f.crew2()).time1Centiseconds(1234).status1(AttemptResultStatus.FINISHED)
                .status2(AttemptResultStatus.DNF).winnerSlot(1).nextHeat(target).nextSlot(1).build());
        var id = source.getId();
        entityManager.clear();
        var loaded = heats.findById(id).orElseThrow();
        assertThat(loaded.getCrew1().getId()).isEqualTo(f.crew1().getId());
        assertThat(loaded.getCrew2().getId()).isEqualTo(f.crew2().getId());
        assertThat(loaded.getTime1Centiseconds()).isEqualTo(1234);
        assertThat(loaded.getStatus1()).isEqualTo(AttemptResultStatus.FINISHED);
        assertThat(loaded.getStatus2()).isEqualTo(AttemptResultStatus.DNF);
        assertThat(loaded.getNextHeat().getId()).isEqualTo(target.getId());
        assertThat(loaded.getNextSlot()).isEqualTo(1);
        assertThat(loaded.getWinnerSlot()).isEqualTo(1);
        assertThat(loaded.getNextHeat().getCrew1()).isNull();
    }

    @Test
    void enforcesScopedIdentityAndReferences() {
        assertInvalid(f -> jdbc.update("insert into parallel_sprint_brackets (discipline_id, category_id) values (?, ?)", f.discipline().getId(), f.category().getId()));
        assertInvalid(f -> { insertHeat(f, 1, 1, null, null); insertHeat(f, 1, 1, null, null); });
        assertInvalid(f -> insertHeat(f, 1, 1, 99999999L, null));
        assertInvalid(f -> insertHeat(f, 1, 1, f.crew1().getId(), f.crew1().getId()));
        assertInvalid(f -> jdbc.update("insert into parallel_sprint_heats (bracket_id, category_id, round_number, heat_number, next_heat_id, next_slot) values (?, ?, 1, 1, ?, 1)", f.bracket().getId(), f.category().getId(), 99999999L));
        assertInvalid(f -> jdbc.update("insert into parallel_sprint_heats (bracket_id, category_id, round_number, heat_number, time_1_centiseconds) values (?, ?, 1, 1, -1)", f.bracket().getId(), f.category().getId()));
    }

    private void assertInvalid(java.util.function.Consumer<Fixture> action) {
        transactions.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var f = fixture();
            assertThatThrownBy(() -> action.accept(f)).isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insertHeat(Fixture f, int round, int heat, Long crew1, Long crew2) {
        jdbc.update("insert into parallel_sprint_heats (bracket_id, category_id, round_number, heat_number, crew_1_id, crew_2_id) values (?, ?, ?, ?, ?, ?)", f.bracket().getId(), f.category().getId(), round, heat, crew1, crew2);
    }

    private Fixture fixture() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.PARALLEL_SPRINT).build());
        discipline.replaceCategories(List.of(category));
        disciplines.flush();
        var crew1 = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("One").regionOrClub("Club").representative("Person").startNumber(1).build());
        var crew2 = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Two").regionOrClub("Club").representative("Person").startNumber(2).build());
        var bracket = brackets.saveAndFlush(ParallelSprintBracket.builder().discipline(discipline).category(category).build());
        return new Fixture(discipline, category, bracket, crew1, crew2);
    }
    private record Fixture(Discipline discipline, CompetitionCategory category, ParallelSprintBracket bracket, CrewEntry crew1, CrewEntry crew2) { }
}
