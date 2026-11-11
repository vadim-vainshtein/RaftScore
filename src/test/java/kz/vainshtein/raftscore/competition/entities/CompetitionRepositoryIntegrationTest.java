package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CompetitionRepositoryIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private CompetitionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void persistsAllCompetitionDetails() {
        var start = LocalDate.of(2026, 7, 1);
        var competition = repository.saveAndFlush(Competition.builder()
                .title("River race").startDate(start).endDate(start.plusDays(2))
                .location("Almaty").timeZone("Asia/Almaty").description("Three days")
                .admissionConfirmationRequired(true).build());
        entityManager.clear();

        assertThat(repository.findById(competition.getId())).hasValueSatisfying(found -> {
            assertThat(found.getTitle()).isEqualTo("River race");
            assertThat(found.getStartDate()).isEqualTo(start);
            assertThat(found.getEndDate()).isEqualTo(start.plusDays(2));
            assertThat(found.getLocation()).isEqualTo("Almaty");
            assertThat(found.getTimeZone()).isEqualTo("Asia/Almaty");
            assertThat(found.getDescription()).isEqualTo("Three days");
            assertThat(found.isAdmissionConfirmationRequired()).isTrue();
            assertThat(found.getVersion()).isZero();
        });
    }

    @Test
    @Transactional
    void persistsSingleDayWithNoDescription() {
        var start = LocalDate.of(2026, 7, 1);
        var competition = repository.saveAndFlush(Competition.builder()
                .title("One day").startDate(start).location("River")
                .timeZone("Europe/London").build());
        entityManager.clear();

        assertThat(repository.findById(competition.getId())).hasValueSatisfying(found -> {
            assertThat(found.getStartDate()).isEqualTo(start);
            assertThat(found.getEndDate()).isEqualTo(start);
            assertThat(found.getDescription()).isNull();
            assertThat(found.isAdmissionConfirmationRequired()).isFalse();
        });
    }

    @Test
    @Transactional
    void databaseRejectsReversedDateRange() {
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                INSERT INTO competitions (title, start_date, end_date, location, time_zone,
                    admission_confirmation_required, version)
                VALUES ('Race', DATE '2026-07-02', DATE '2026-07-01', 'River', 'Europe/London', false, 0)
                """).executeUpdate()).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }
}
