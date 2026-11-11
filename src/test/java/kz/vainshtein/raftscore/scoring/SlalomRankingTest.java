package kz.vainshtein.raftscore.scoring;

import java.util.ArrayList;
import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfiguration;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty;
import kz.vainshtein.raftscore.startlist.entities.StartSlot;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlalomRankingTest {
    private final Discipline discipline = mock(Discipline.class);
    private final SlalomConfiguration config = mock(SlalomConfiguration.class);
    private final List<StartSlot> slots = new ArrayList<>();
    private final List<AttemptResult> attempts = new ArrayList<>();
    private final List<SlalomGatePenalty> gates = new ArrayList<>();

    SlalomRankingTest() {
        when(config.getDiscipline()).thenReturn(discipline);
        when(config.getAttemptCount()).thenReturn(2);
        when(config.getGateCount()).thenReturn(1);
    }

    @Test void comparesBestThenWorstThenBestPenaltyThenStartOrder() {
        var a = crew(1); var b = crew(2); var c = crew(3); var d = crew(4); var e = crew(5);
        finish(a, 1, 1000, 5); finish(a, 2, 2000, 0);
        finish(b, 1, 1500, 0); finish(b, 2, 1900, 0);
        finish(c, 1, 1000, 5); finish(c, 2, 1900, 0);
        finish(d, 1, 1500, 0); finish(d, 2, 1900, 0);
        finish(e, 1, 1499, 0);
        assertThat(rank()).extracting(SlalomRanking.Result::crew).containsExactly(e, b, d, c, a);
        assertThat(rank()).extracting(SlalomRanking.Result::place).containsExactly(1, 2, 3, 4, 5);
    }

    @Test void missingAndNonFinishingWorstAttemptsSortAfterFinishedWorst() {
        var missing = crew(1); var dnf = crew(2); var complete = crew(3);
        finish(missing, 1, 1000, 0); finish(dnf, 1, 1000, 0); status(dnf, 2, AttemptResultStatus.DNF);
        finish(complete, 1, 1000, 0); finish(complete, 2, 3000, 0);
        assertThat(rank()).extracting(SlalomRanking.Result::crew).containsExactly(complete, missing, dnf);
    }

    @Test void disqualificationOverridesFinishAndOtherStatusesHaveNoPlace() {
        var eligible = crew(1); var disciplineDsq = crew(2); var competitionDsq = crew(3); var nonfinish = crew(4);
        finish(eligible, 1, 1000, 0); status(eligible, 2, AttemptResultStatus.DSQ_ATTEMPT);
        finish(disciplineDsq, 1, 500, 0); status(disciplineDsq, 2, AttemptResultStatus.DSQ_DISCIPLINE);
        status(competitionDsq, 1, AttemptResultStatus.DSQ_DISCIPLINE); status(competitionDsq, 2, AttemptResultStatus.DSQ_COMPETITION);
        status(nonfinish, 1, AttemptResultStatus.DNS); status(nonfinish, 2, AttemptResultStatus.DNF);
        assertThat(rank()).extracting(SlalomRanking.Result::status).containsExactly(AttemptResultStatus.FINISHED,
                AttemptResultStatus.DSQ_DISCIPLINE, AttemptResultStatus.DSQ_COMPETITION, AttemptResultStatus.DNF);
        assertThat(rank()).extracting(SlalomRanking.Result::place).containsExactly(1, null, null, null);
    }

    @Test void retainsLongTotalsAndRejectsDuplicateAttemptNumbers() {
        var crew = crew(1); finish(crew, 1, Integer.MAX_VALUE, 50);
        assertThat(rank().getFirst().totalCentiseconds()).isEqualTo(2147488647L);
        finish(crew, 1, 1000, 0);
        assertThatIllegalArgumentException().isThrownBy(this::rank);
    }

    private List<SlalomRanking.Result> rank() { return SlalomRanking.rank(config, slots, attempts, gates); }
    private CrewEntry crew(int position) {
        var crew = mock(CrewEntry.class); var slot = mock(StartSlot.class);
        when(slot.getCrew()).thenReturn(crew); when(slot.getPosition()).thenReturn(position); slots.add(slot); return crew;
    }
    private void finish(CrewEntry crew, int number, int duration, int penalty) {
        var attempt = status(crew, number, AttemptResultStatus.FINISHED);
        when(attempt.getDurationCentiseconds()).thenReturn(duration);
        var gate = mock(SlalomGatePenalty.class);
        when(gate.getAttempt()).thenReturn(attempt); when(gate.getConfiguration()).thenReturn(config);
        when(gate.getGateNumber()).thenReturn(1); when(gate.getPenaltySeconds()).thenReturn(penalty); gates.add(gate);
    }
    private AttemptResult status(CrewEntry crew, int number, AttemptResultStatus status) {
        var attempt = mock(AttemptResult.class); when(attempt.getCrewEntry()).thenReturn(crew);
        when(attempt.getDiscipline()).thenReturn(discipline); when(attempt.getAttemptNumber()).thenReturn(number);
        when(attempt.getStatus()).thenReturn(status); attempts.add(attempt); return attempt;
    }
}
