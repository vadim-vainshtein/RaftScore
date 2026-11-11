package kz.vainshtein.raftscore.timing.entities;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
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
class AttemptResultRepositoryIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired private AttemptResultRepository results;
    @Autowired private CompetitionRepository competitions;
    @Autowired private CompetitionCategoryRepository categories;
    @Autowired private DisciplineRepository disciplines;
    @Autowired private CrewEntryRepository crews;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    @Test
    @Transactional
    void roundTripsAllStatusesTimesAndVersionedUpdates() {
        var fixture = fixture();
        var start = Instant.parse("2026-07-01T12:34:56.123456Z");
        var finish = start.plusSeconds(12).plusMillis(340);
        for (var status : AttemptResultStatus.values()) {
            var duration = status == AttemptResultStatus.DNS || status == AttemptResultStatus.DNF ? null : 1234;
            var resultStart = status == AttemptResultStatus.DNS ? null : start;
            var resultFinish = status == AttemptResultStatus.DNS || status == AttemptResultStatus.DNF ? null : finish;
            var penalty = status == AttemptResultStatus.DNS ? 0 : 25;
            results.save(result(fixture, status.ordinal() + 1, duration, resultStart, resultFinish, penalty, status));
        }
        entityManager.flush();
        entityManager.clear();
        var loaded = results.findAll();
        assertThat(loaded).hasSize(6).extracting(AttemptResult::getStatus)
                .containsExactlyInAnyOrder(AttemptResultStatus.values());
        var first = loaded.stream().filter(r -> r.getStatus() == AttemptResultStatus.FINISHED).findFirst().orElseThrow();
        assertThat(first.getDurationCentiseconds()).isEqualTo(1234);
        assertThat(first.getStartInstant()).isEqualTo(start);
        assertThat(first.getFinishInstant()).isEqualTo(finish);
        assertThat(first.getPenaltyCentiseconds()).isEqualTo(25);
        assertThat(first.getDiscipline().getId()).isEqualTo(fixture.discipline().getId());
        assertThat(first.getCrewEntry().getId()).isEqualTo(fixture.crew().getId());
        first.updateResult(null, null, null, 0, AttemptResultStatus.DNF);
        entityManager.flush();
        var id = first.getId();
        entityManager.clear();
        var updated = results.findById(id).orElseThrow();
        assertThat(updated.getVersion()).isOne();
        assertThat(updated.getDurationCentiseconds()).isNull();
        assertThat(updated.getStartInstant()).isNull();
        assertThat(updated.getFinishInstant()).isNull();
        assertThat(updated.getStatus()).isEqualTo(AttemptResultStatus.DNF);
    }

    @Test
    @Transactional
    void rejectsInvalidEntityValuesAndUnselectedCategory() {
        var fixture = fixture();
        assertThatThrownBy(() -> result(fixture, 0, null, null, null, 0, AttemptResultStatus.DNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(fixture, 1, -1, null, null, 0, AttemptResultStatus.DNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(fixture, 1, null, null, null, -1, AttemptResultStatus.DNS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(fixture, 1, null, null, null, 0, null))
                .isInstanceOf(NullPointerException.class);
        fixture.discipline().replaceCategories(List.of());
        assertThatThrownBy(() -> result(fixture, 1, null, null, null, 0, AttemptResultStatus.DNS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void retainsConsistentTimingAcrossPostgresRoundTrip() {
        var fixture = fixture();
        var start = Instant.parse("2026-07-01T12:00:00.000000400Z");
        var finish = Instant.parse("2026-07-01T12:00:00.009999600Z");
        var result = results.saveAndFlush(result(fixture, 1, null, start, finish, 0, AttemptResultStatus.FINISHED));
        var id = result.getId();
        entityManager.clear();

        var loaded = results.findById(id).orElseThrow();
        assertThat(loaded.getDurationCentiseconds()).isZero();
        assertThat(loaded.getStartInstant()).isEqualTo(Instant.parse("2026-07-01T12:00:00Z"));
        assertThat(loaded.getFinishInstant()).isEqualTo(Instant.parse("2026-07-01T12:00:00.009999Z"));
        loaded.updateResult(loaded.getDurationCentiseconds(), loaded.getStartInstant(), loaded.getFinishInstant(),
                loaded.getPenaltyCentiseconds(), loaded.getStatus());
        entityManager.flush();
        assertInvalidUpdate(loaded, 0, start.plusNanos(200), start, 0, AttemptResultStatus.FINISHED);
    }

    @Test
    @Transactional
    void validatesStatusTimingCombinationsAndKeepsRejectedUpdatesUnchanged() {
        var fixture = fixture();
        var start = Instant.parse("2026-07-01T12:00:00Z");
        var finish = start.plusMillis(1234);
        var result = result(fixture, 1, 123, start, finish, 5, AttemptResultStatus.FINISHED);
        for (var status : AttemptResultStatus.values()) {
            if (status == AttemptResultStatus.FINISHED) {
                result.updateResult(123, null, null, 5, status);
                result.updateResult(null, start, finish, 5, status);
                assertThat(result.getDurationCentiseconds()).isEqualTo(123);
                result.updateResult(123, start, finish, 5, status);
                assertInvalidUpdate(result, null, start, null, 0, status);
            } else if (status == AttemptResultStatus.DNS) {
                result.updateResult(null, null, null, 0, status);
                assertInvalidUpdate(result, null, start, null, 0, status);
                assertInvalidUpdate(result, null, null, null, 1, status);
            } else if (status == AttemptResultStatus.DNF) {
                result.updateResult(null, start, null, 5, status);
                result.updateResult(null, null, null, 0, status);
                assertInvalidUpdate(result, 123, null, null, 0, status);
                assertInvalidUpdate(result, null, start, finish, 0, status);
            } else {
                result.updateResult(null, null, null, 5, status);
                result.updateResult(null, start, null, 5, status);
                result.updateResult(123, null, null, 5, status);
                result.updateResult(null, start, finish, 5, status);
                assertThat(result.getDurationCentiseconds()).isEqualTo(123);
                result.updateResult(123, start, finish, 5, status);
            }
            assertInvalidUpdate(result, null, null, finish, 0, status);
            assertInvalidUpdate(result, null, finish, start, 0, status);
            assertInvalidUpdate(result, 124, start, finish, 0, status);
            assertInvalidUpdate(result, 123, start, null, 0, status);
            assertInvalidUpdate(result, null, start, start.plusSeconds(21_474_837), 0, status);
            assertInvalidUpdate(result, null, Instant.MIN, Instant.MAX, 0, status);
        }
        results.saveAndFlush(result);
        entityManager.clear();
        assertThat(results.findById(result.getId()).orElseThrow().getStatus())
                .isEqualTo(AttemptResultStatus.DSQ_COMPETITION);
    }

    private void assertInvalidUpdate(AttemptResult result, Integer duration, Instant start, Instant finish,
                                     int penalty, AttemptResultStatus status) {
        var oldDuration = result.getDurationCentiseconds();
        var oldStart = result.getStartInstant();
        var oldFinish = result.getFinishInstant();
        var oldPenalty = result.getPenaltyCentiseconds();
        var oldStatus = result.getStatus();
        assertThatThrownBy(() -> result.updateResult(duration, start, finish, penalty, status))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(result.getDurationCentiseconds()).isEqualTo(oldDuration);
        assertThat(result.getStartInstant()).isEqualTo(oldStart);
        assertThat(result.getFinishInstant()).isEqualTo(oldFinish);
        assertThat(result.getPenaltyCentiseconds()).isEqualTo(oldPenalty);
        assertThat(result.getStatus()).isEqualTo(oldStatus);
    }

    @Test
    void enforcesDatabaseInvariants() {
        assertInvalidInsert(0, null, 0, "DNS", false);
        assertInvalidInsert(1, -1, 0, "DNS", false);
        assertInvalidInsert(1, null, -1, "DNS", false);
        assertInvalidInsert(1, null, 0, "INVALID", false);
        assertInvalidInsert(1, null, 0, null, false);
        assertInvalidInsert(1, null, 0, "DNS", true);
    }

    private void assertInvalidInsert(int number, Integer duration, int penalty, String status, boolean duplicate) {
        transactions.executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            var fixture = fixture();
            if (duplicate) insert(fixture, number, duration, penalty, status);
            assertThatThrownBy(() -> insert(fixture, number, duration, penalty, status))
                    .isInstanceOf(DataIntegrityViolationException.class);
        });
    }

    private void insert(Fixture fixture, int number, Integer duration, int penalty, String status) {
        jdbc.update("INSERT INTO attempt_results (discipline_id, crew_entry_id, attempt_number, duration_centiseconds, penalty_centiseconds, status) VALUES (?, ?, ?, ?, ?, ?)",
                fixture.discipline().getId(), fixture.crew().getId(), number, duration, penalty, status);
    }

    private AttemptResult result(Fixture fixture, int number, Integer duration, Instant start, Instant finish,
                                 int penalty, AttemptResultStatus status) {
        return AttemptResult.builder().discipline(fixture.discipline()).crewEntry(fixture.crew())
                .attemptNumber(number).durationCentiseconds(duration).startInstant(start).finishInstant(finish)
                .penaltyCentiseconds(penalty).status(status).build();
    }

    private Fixture fixture() {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint")
                .type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category));
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew")
                .regionOrClub("Club").representative("Person").startNumber(1).build());
        return new Fixture(discipline, crew);
    }

    private record Fixture(Discipline discipline, CrewEntry crew) { }
}
