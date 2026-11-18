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
    private final kz.vainshtein.raftscore.entry.services.CrewEligibilityService eligibility;
    private final EntityManager entityManager;
    private final kz.vainshtein.raftscore.scoring.RecalculationService recalculation;
    private final kz.vainshtein.raftscore.discipline.entities.DisciplineRepository disciplines;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ParallelSprintProgressionService progression;

    public static class DependentHeats extends RuntimeException {
        public final java.util.List<Long> ids;
        public DependentHeats(java.util.List<Long> ids) { this.ids = ids; }
    }

    @Transactional
    public void annul(long heatId, long expectedVersion, String reason) {
        var heat = heats.findById(heatId).orElseThrow();
        var discipline = disciplines.findForStartListUpdate(heat.getBracket().getDiscipline().getId()).orElseThrow();
        discipline.requireStarted();
        entityManager.refresh(heat);
        if (heat.getVersion() != expectedVersion) throw new IllegalStateException("heat has changed");
        if (heat.getStatus1() == null && heat.getStatus2() == null) throw new IllegalStateException("heat has no recorded result");
        var next = heat.getNextHeat();
        var bronze = next != null && next.getNextHeat() == null && next.getHeatNumber() == 1
                ? heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(heat.getBracket().getId()).stream()
                    .filter(h -> h.getRoundNumber() == next.getRoundNumber() && h.getHeatNumber() == 2).findFirst().orElse(null)
                : null;
        var dependent = java.util.stream.Stream.of(next, bronze).filter(java.util.Objects::nonNull)
                .filter(h -> h.getStatus1() != null || h.getStatus2() != null || h.isWinnerOverridden())
                .map(h -> h.getId()).toList();
        if (!dependent.isEmpty()) throw new DependentHeats(dependent);
        jdbc.update("""
                INSERT INTO result_annulments (competition_id, discipline_id, category_id, crew_id, result_snapshot, reason, annulled_at)
                SELECT ?, ?, ?, participant.crew_id, to_jsonb(h)::text, ?, CURRENT_TIMESTAMP
                FROM parallel_sprint_heats h
                CROSS JOIN LATERAL (VALUES (h.crew_1_id), (h.crew_2_id)) AS participant(crew_id)
                WHERE h.id = ? AND participant.crew_id IS NOT NULL
                """, discipline.getCompetition().getId(), discipline.getId(), heat.getBracket().getCategory().getId(), reason.trim(), heatId);
        var before = progression.outcome(heat);
        heat.record(null, null, null, null, null, false);
        progression.propagate(heat, before);
        heats.flush();
        recalculation.recalculateLocked(discipline, heat.getBracket().getCategory().getId());
    }

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
        var discipline = disciplines.findForStartListUpdate(heat.getBracket().getDiscipline().getId()).orElseThrow();
        discipline.requireStarted();
        entityManager.refresh(heat);
        if (heat.getVersion() != expectedVersion) {
            if (expectedVersion < Long.MAX_VALUE && heat.getVersion() == expectedVersion + 1
                    && heat.getCrew1() != null && heat.getCrew2() != null && matches(heat, first, second, winnerCrewId)) return;
            throw new IllegalStateException("heat has changed");
        }
        if (heat.getCrew1() == null || heat.getCrew2() == null)
            throw new IllegalStateException("heat must have two crews; seeded byes cannot be edited");
        eligibility.requireEligible(heat.getCrew1());
        eligibility.requireEligible(heat.getCrew2());
        Integer winner = winnerCrewId == null ? winner(first, second) : overrideSlot(heat, winnerCrewId);
        if (winnerCrewId != null) {
            var status = winner == 1 ? first.status() : second.status();
            if (status == AttemptResultStatus.DSQ_DISCIPLINE || status == AttemptResultStatus.DSQ_COMPETITION)
                throw new IllegalArgumentException("disqualified crew cannot win");
        }
        var before = progression.outcome(heat);
        heat.record(first.status(), first.timeCentiseconds(), second.status(), second.timeCentiseconds(), winner, winnerCrewId != null);
        progression.propagate(heat, before);
        heats.flush();
        if (heat.getVersion() == expectedVersion) entityManager.lock(heat, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        recalculation.recalculateLocked(heat.getBracket().getDiscipline(), heat.getBracket().getCategory().getId());
    }

    private boolean matches(kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat heat, Result first, Result second, Long winnerCrewId) {
        var winner = winnerCrewId == null ? winner(first, second) : overrideSlot(heat, winnerCrewId);
        return heat.getStatus1() == first.status() && java.util.Objects.equals(heat.getTime1Centiseconds(), first.timeCentiseconds())
                && heat.getStatus2() == second.status() && java.util.Objects.equals(heat.getTime2Centiseconds(), second.timeCentiseconds())
                && java.util.Objects.equals(heat.getWinnerSlot(), winner) && heat.isWinnerOverridden() == (winnerCrewId != null);
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
