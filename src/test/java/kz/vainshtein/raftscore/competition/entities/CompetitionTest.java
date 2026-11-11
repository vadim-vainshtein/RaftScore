package kz.vainshtein.raftscore.competition.entities;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CompetitionTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);

    private Competition.CompetitionBuilder validCompetition() {
        return Competition.builder().title("River race").startDate(START)
                .location("Almaty").timeZone("Asia/Almaty");
    }

    @Test
    void defaultsEndDateToStartForSingleDay() {
        var competition = validCompetition().build();
        assertThat(competition.getEndDate()).isEqualTo(START);
        assertThat(competition.getDescription()).isNull();
    }

    @Test
    void acceptsAnInclusiveDateRange() {
        var end = START.plusDays(3);
        assertThat(validCompetition().endDate(end).build().getEndDate()).isEqualTo(end);
    }

    @Test
    void rejectsInvalidDatesAndRequiredText() {
        assertThatNullPointerException().isThrownBy(() -> validCompetition().startDate(null).build());
        assertThatIllegalArgumentException().isThrownBy(() -> validCompetition().endDate(START.minusDays(1)).build());
        assertThatIllegalArgumentException().isThrownBy(() -> validCompetition().title(" ").build());
        assertThatIllegalArgumentException().isThrownBy(() -> validCompetition().location(null).build());
    }

    @Test
    void acceptsNamedFixedIanaTimeZones() {
        assertThat(validCompetition().timeZone("Etc/UTC").build().getTimeZone()).isEqualTo("Etc/UTC");
        assertThat(validCompetition().timeZone("Etc/GMT+6").build().getTimeZone()).isEqualTo("Etc/GMT+6");
    }

    @Test
    void rejectsInvalidAndFixedOffsetTimeZones() {
        for (var zone : new String[] {null, "", "Mars/Olympus", "+06:00", "UTC+06:00", "Z", "SystemV/AST4"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> validCompetition().timeZone(zone).build());
        }
    }
}
