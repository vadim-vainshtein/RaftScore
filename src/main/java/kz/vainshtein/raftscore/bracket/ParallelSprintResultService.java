package kz.vainshtein.raftscore.bracket;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records both results and advances the winner as one transaction. */
@Service
@RequiredArgsConstructor
public class ParallelSprintResultService {
    private final ParallelSprintHeatRepository heats;
    private final EntityManager entityManager;
    private final kz.vainshtein.raftscore.discipline.entities.DisciplineRepository disciplines;

    public record Result(AttemptResultStatus status, Integer timeCentiseconds) {
        public Result {
            if (status == null) throw new IllegalArgumentException("status is required");
            if (status == AttemptResultStatus.FINISHED) {
                if (timeCentiseconds == null || timeCentiseconds < 0)
                    throw new IllegalArgumentException("finished result requires a nonnegative time");
            } else if (timeCentiseconds != null) {
                throw new IllegalArgumentException("non-finished result cannot have a time");
            }
        }
    }

    @Transactional
    public void record(long heatId, long expectedVersion, Result first, Result second) {
        record(heatId, expectedVersion, first, second, null);
    }

    @Transactional
    public void record(long heatId, long expectedVersion, Result first, Result second, Long winnerCrewId) {
        if (first == null || second == null) throw new IllegalArgumentException("both results are required");
        var heat = heats.findById(heatId).orElseThrow();
        // All result writers must acquire this lock before changing source results.
        disciplines.findForStartListUpdate(heat.getBracket().getDiscipline().getId()).orElseThrow();
        entityManager.refresh(heat);
        if (heat.getVersion() != expectedVersion) throw new IllegalStateException("heat has changed");
        if (heat.getCrew1() == null || heat.getCrew2() == null)
            throw new IllegalStateException("heat must have two crews; seeded byes cannot be edited");
        Integer winner = winnerCrewId == null ? winner(first, second) : overrideSlot(heat, winnerCrewId);
        if (winnerCrewId != null) {
            var status = winner == 1 ? first.status() : second.status();
            if (status == AttemptResultStatus.DSQ_DISCIPLINE || status == AttemptResultStatus.DSQ_COMPETITION)
                throw new IllegalArgumentException("disqualified crew cannot win");
        }
        var oldWinner = heat.getWinnerSlot() == null ? null :
                heat.getWinnerSlot() == 1 ? heat.getCrew1() : heat.getCrew2();
        var next = heat.getNextHeat();
        if (next != null && !java.util.Objects.equals(heat.getWinnerSlot(), winner)) {
            entityManager.lock(next, LockModeType.OPTIMISTIC);
            next.advance(heat.getNextSlot(), oldWinner, winner == null ? null : winner == 1 ? heat.getCrew1() : heat.getCrew2());
        }
        heat.record(first.status(), first.timeCentiseconds(), second.status(), second.timeCentiseconds(), winner, winnerCrewId != null);
        heats.flush();
    }

    private Integer overrideSlot(kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat heat, long crewId) {
        if (heat.getCrew1().getId().equals(crewId)) return 1;
        if (heat.getCrew2().getId().equals(crewId)) return 2;
        throw new IllegalArgumentException("winner must be a crew in the heat");
    }

    private Integer winner(Result first, Result second) {
        boolean oneFinished = first.status() == AttemptResultStatus.FINISHED;
        boolean twoFinished = second.status() == AttemptResultStatus.FINISHED;
        if (!oneFinished && !twoFinished) return null;
        if (!oneFinished) return 2;
        if (!twoFinished) return 1;
        int comparison = Integer.compare(first.timeCentiseconds(), second.timeCentiseconds());
        if (comparison == 0) return null;
        return comparison < 0 ? 1 : 2;
    }
}
