package kz.vainshtein.raftscore.slalom;

import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfiguration;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlalomAttemptCalculatorTest {
    private final Discipline discipline = mock(Discipline.class);
    private final SlalomConfiguration configuration = mock(SlalomConfiguration.class);
    private final AttemptResult attempt = mock(AttemptResult.class);

    SlalomAttemptCalculatorTest() {
        when(configuration.getDiscipline()).thenReturn(discipline);
        when(configuration.getGateCount()).thenReturn(3);
        when(configuration.getAttemptCount()).thenReturn(2);
        when(attempt.getDiscipline()).thenReturn(discipline);
        when(attempt.getAttemptNumber()).thenReturn(1);
        when(attempt.getStatus()).thenReturn(AttemptResultStatus.FINISHED);
        when(attempt.getDurationCentiseconds()).thenReturn(Integer.MAX_VALUE);
    }

    @Test
    void sumsSecondsInCentisecondsWithoutIntegerOverflow() {
        var result = SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 0), gate(2, 5), gate(3, 50)));
        assertThat(result.status()).isEqualTo(AttemptResultStatus.FINISHED);
        assertThat(result.totalCentiseconds()).isEqualTo(2147489147L);
    }

    @Test
    void preservesEveryNonFinishingStatusWithoutRankableTime() {
        for (var status : AttemptResultStatus.values()) {
            if (status == AttemptResultStatus.FINISHED) continue;
            when(attempt.getStatus()).thenReturn(status);
            assertThat(SlalomAttemptCalculator.calculate(configuration, attempt, List.of()))
                    .isEqualTo(new SlalomAttemptCalculator.Result(status, null));
        }
        when(attempt.getStatus()).thenReturn(AttemptResultStatus.DNF);
        assertThat(SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 5))).totalCentiseconds()).isNull();
        when(attempt.getStatus()).thenReturn(AttemptResultStatus.DNS);
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 0))));
    }

    @Test
    void rejectsIncompleteDuplicateInvalidAndForeignGates() {
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 0))));
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 0), gate(1, 5), gate(3, 50))));
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(4, 0))));
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(gate(1, 10))));
        var foreign = gate(1, 0);
        when(foreign.getAttempt()).thenReturn(mock(AttemptResult.class));
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of(foreign)));
    }

    @Test
    void rejectsOutOfRangeAttemptAndGenericPenalty() {
        when(attempt.getAttemptNumber()).thenReturn(3);
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of()));
        when(attempt.getAttemptNumber()).thenReturn(1);
        when(attempt.getPenaltyCentiseconds()).thenReturn(5);
        assertThatIllegalArgumentException().isThrownBy(() -> SlalomAttemptCalculator.calculate(configuration, attempt, List.of()));
    }

    private SlalomGatePenalty gate(int number, int seconds) {
        var gate = mock(SlalomGatePenalty.class);
        when(gate.getConfiguration()).thenReturn(configuration);
        when(gate.getAttempt()).thenReturn(attempt);
        when(gate.getGateNumber()).thenReturn(number);
        when(gate.getPenaltySeconds()).thenReturn(seconds);
        return gate;
    }
}
