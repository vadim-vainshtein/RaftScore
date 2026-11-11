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
        if (first == null || second == null) throw new IllegalArgumentException("both results are required");
        var heat = heats.findById(heatId).orElseThrow();
        if (heat.getVersion() != expectedVersion) throw new IllegalStateException("heat has changed");
        if (heat.getCrew1() == null || heat.getCrew2() == null)
            throw new IllegalStateException("heat must have two crews; seeded byes cannot be edited");
        int winner = winner(first, second);
        var oldWinner = heat.getWinnerSlot() == null ? null :
                heat.getWinnerSlot() == 1 ? heat.getCrew1() : heat.getCrew2();
        var next = heat.getNextHeat();
        if (next != null && (heat.getWinnerSlot() == null || heat.getWinnerSlot() != winner)) {
            entityManager.lock(next, LockModeType.OPTIMISTIC);
            next.advance(heat.getNextSlot(), oldWinner, winner == 1 ? heat.getCrew1() : heat.getCrew2());
        }
        heat.record(first.status(), first.timeCentiseconds(), second.status(), second.timeCentiseconds(), winner);
        heats.flush();
    }

    private int winner(Result first, Result second) {
        boolean oneFinished = first.status() == AttemptResultStatus.FINISHED;
        boolean twoFinished = second.status() == AttemptResultStatus.FINISHED;
        if (!oneFinished && !twoFinished) throw new IllegalArgumentException("heat has no valid winner");
        if (!oneFinished) return 2;
        if (!twoFinished) return 1;
        int comparison = Integer.compare(first.timeCentiseconds(), second.timeCentiseconds());
        if (comparison == 0) throw new IllegalArgumentException("tied heat requires a judicial decision");
        return comparison < 0 ? 1 : 2;
    }
}
