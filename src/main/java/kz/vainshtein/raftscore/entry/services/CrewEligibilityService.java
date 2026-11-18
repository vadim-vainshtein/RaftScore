package kz.vainshtein.raftscore.entry.services;

import kz.vainshtein.raftscore.entry.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes eligibility from current competition configuration and crew data. */
@Service
@RequiredArgsConstructor
public class CrewEligibilityService {
    private final CrewEligibilityPolicy policy;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository heats;
    private final kz.vainshtein.raftscore.discipline.entities.DisciplineRepository disciplines;
    private final kz.vainshtein.raftscore.scoring.RecalculationService recalculation;
    private final kz.vainshtein.raftscore.bracket.ParallelSprintProgressionService progression;

    /** A newly eligible crew cannot be entered into an already closed discipline. */
    public void requireNoNewEligibleCrew(CrewEntry entry, boolean wasEligible) {
        if (wasEligible || !isEligible(entry)) return;
        var closed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM discipline_categories dc JOIN disciplines d ON d.id = dc.discipline_id
                               WHERE dc.category_id = ? AND d.status = 'COMPLETED')
                """, Boolean.class, entry.getCompetitionCategory().getId());
        if (Boolean.TRUE.equals(closed)) throw new CompletedDiscipline();
    }

    public static class CompletedDiscipline extends RuntimeException { }

    public void requireNoRecordedResults(long crewId) {
        var recorded = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM attempt_results WHERE crew_entry_id = ?)
                    OR EXISTS (SELECT 1 FROM parallel_sprint_heats
                               WHERE (crew_1_id = ? OR crew_2_id = ?)
                                 AND (status_1 IS NOT NULL OR status_2 IS NOT NULL OR winner_overridden))
                """, Boolean.class, crewId, crewId, crewId);
        if (Boolean.TRUE.equals(recorded)) throw new RecordedResults();
    }

    public static class RecordedResults extends RuntimeException { }

    public void withdrawFromUnplayedBrackets(long crewId) {
        var ids = jdbc.queryForList("""
                SELECT h.id FROM parallel_sprint_heats h JOIN parallel_sprint_brackets b ON b.id = h.bracket_id
                WHERE h.crew_1_id = ? OR h.crew_2_id = ? ORDER BY b.discipline_id, h.round_number, h.id
                """, Long.class, crewId, crewId);
        var all = ids.stream().map(id -> heats.findById(id).orElseThrow()).toList();
        for (var heat : all) disciplines.findForStartListUpdate(heat.getBracket().getDiscipline().getId()).orElseThrow();
        for (var heat : all) {
            if (heat.getRoundNumber() != 1) continue;
            var before = progression.outcome(heat);
            try {
                heat.withdrawFirstRound(crewId);
                progression.propagate(heat, before);
            } catch (IllegalStateException ex) { throw new RecordedResults(); }
        }
        if (all.stream().anyMatch(h -> (h.getCrew1() != null && h.getCrew1().getId().equals(crewId))
                || (h.getCrew2() != null && h.getCrew2().getId().equals(crewId)))) throw new RecordedResults();
        heats.flush();
        for (var heat : all.stream().filter(h -> h.getRoundNumber() == 1).toList())
            recalculation.recalculateLocked(heat.getBracket().getDiscipline(), heat.getCategory().getId());
    }

    public void requireEligible(CrewEntry entry) {
        if (!isEligible(entry)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "crew is not eligible");
    }

    @Transactional(readOnly = true)
    public boolean isEligible(CrewEntry entry) {
        return policy.isEligible(entry);
    }
}
