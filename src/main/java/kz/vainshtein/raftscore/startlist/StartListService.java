package kz.vainshtein.raftscore.startlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import kz.vainshtein.raftscore.entry.services.CrewEligibilityService;
import kz.vainshtein.raftscore.startlist.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StartListService {
    private final DisciplineRepository disciplines;
    private final CompetitionCategoryRepository categories;
    private final CrewEntryRepository crews;
    private final CrewEligibilityService eligibility;
    private final kz.vainshtein.raftscore.standings.OverallStandingsService standings;
    private final StartListRepository lists;
    private final StartSlotRepository slots;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final kz.vainshtein.raftscore.scoring.RecalculationService recalculation;

    public record Slot(long crewId, String crewName, int startNumber, int position, Instant plannedTime) { }
    public record View(Long id, List<Slot> slots, boolean locked) { }
    public record Annulment(long crewId, String result, String reason, java.time.OffsetDateTime annulledAt) { }

    @Transactional(readOnly = true)
    public List<Annulment> annulments(long competitionId, long disciplineId, long categoryId) {
        selection(competitionId, disciplineId, categoryId, false);
        return jdbc.query("SELECT crew_id, result_snapshot, reason, annulled_at FROM result_annulments WHERE competition_id = ? AND discipline_id = ? AND category_id = ? ORDER BY id DESC",
                (row, number) -> new Annulment(row.getLong(1), row.getString(2), row.getString(3), row.getObject(4, java.time.OffsetDateTime.class)),
                competitionId, disciplineId, categoryId);
    }

    @Transactional(readOnly = true)
    public View read(long competitionId, long disciplineId, long categoryId) {
        selection(competitionId, disciplineId, categoryId, false);
        return lists.findByDisciplineIdAndCategoryId(disciplineId, categoryId)
                .map(this::view).orElseGet(() -> new View(null, List.of(), hasResults(disciplineId, categoryId)));
    }

    /** Serializes replacements on the discipline, including initial list creation. */
    @Transactional
    public View replace(long competitionId, long disciplineId, long categoryId, List<Long> order,
                        boolean random, Instant firstStart, Long intervalSeconds) {
        return replace(competitionId, disciplineId, categoryId, order, random, false, firstStart, intervalSeconds);
    }

    @Transactional
    public View replace(long competitionId, long disciplineId, long categoryId, List<Long> order,
                        boolean random, boolean reverseOverall, Instant firstStart, Long intervalSeconds) {
        var discipline = selection(competitionId, disciplineId, categoryId, true);
        if (hasResults(disciplineId, categoryId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "remove source results before replacing the start list");
        if ((firstStart == null) != (intervalSeconds == null) || (intervalSeconds != null && intervalSeconds < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "firstStart and non-negative intervalSeconds must be supplied together");
        var eligible = crews.findByCompetitionCategoryId(categoryId).stream().filter(eligibility::isEligible).toList();
        var byId = eligible.stream().collect(java.util.stream.Collectors.toMap(c -> c.getId(), c -> c));
        var ids = eligible.stream().map(c -> c.getId()).toList();
        List<Long> selected;
        if (reverseOverall) {
            if (random || order != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reverse overall order must not include random or crew IDs");
            var places = standings.standings(categoryId).stream().filter(s -> s.place() != null)
                    .collect(java.util.stream.Collectors.toMap(s -> s.crewId(), s -> s.place()));
            selected = eligible.stream().sorted(java.util.Comparator
                    .comparing((kz.vainshtein.raftscore.entry.entities.CrewEntry c) -> places.get(c.getId()),
                            java.util.Comparator.nullsFirst(java.util.Comparator.reverseOrder()))
                    .thenComparing(c -> c.getStartNumber()).thenComparing(c -> c.getId()))
                    .map(c -> c.getId()).toList();
        } else if (random) {
            if (order != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "random order must not include crew IDs");
            selected = new ArrayList<>(ids);
            Collections.shuffle(selected);
        } else {
            if (order == null || order.size() != ids.size() || new HashSet<>(order).size() != order.size()
                    || !new HashSet<>(ids).equals(new HashSet<>(order)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order must contain each eligible crew exactly once");
            selected = order;
        }
        var list = lists.findByDisciplineIdAndCategoryId(disciplineId, categoryId).orElseGet(() ->
                lists.save(StartList.builder().discipline(discipline).category(categories.findById(categoryId).orElseThrow()).build()));
        slots.deleteByStartListId(list.getId());
        slots.flush();
        for (int i = 0; i < selected.size(); i++) {
            var id = selected.get(i);
            var crew = byId.get(id);
            Instant planned;
            try { planned = firstStart == null ? null : firstStart.plusSeconds(Math.multiplyExact(intervalSeconds, i)); }
            catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planned time out of range", ex); }
            slots.save(StartSlot.builder().startList(list).crew(crew).position(i + 1).plannedTime(planned).build());
        }
        slots.flush();
        return view(list);
    }

    private boolean hasResults(long disciplineId, long categoryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM attempt_results a JOIN crew_entries c ON c.id = a.crew_entry_id
                               WHERE a.discipline_id = ? AND c.competition_category_id = ?)
                    OR EXISTS (SELECT 1 FROM parallel_sprint_heats h JOIN parallel_sprint_brackets b ON b.id = h.bracket_id
                               WHERE b.discipline_id = ? AND b.category_id = ? AND (h.status_1 IS NOT NULL OR h.status_2 IS NOT NULL OR h.winner_overridden))
                """, Boolean.class, disciplineId, categoryId, disciplineId, categoryId));
    }

    /** Clears source results and their derived data under the same lock as replacements and result entry. */
    @Transactional
    public void clearResults(long competitionId, long disciplineId, long categoryId, long crewId, int attemptNumber, long version, String reason) {
        var discipline = selection(competitionId, disciplineId, categoryId, true);
        discipline.requireStarted();
        if (reason == null || reason.isBlank() || reason.length() > 2000 || reason.indexOf(0) >= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "annulment reason is required");
        if (discipline.getType() == kz.vainshtein.raftscore.discipline.entities.DisciplineType.PARALLEL_SPRINT)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "parallel heat annulment is not supported");
        var count = jdbc.update("""
                INSERT INTO result_annulments (competition_id, discipline_id, category_id, crew_id, result_snapshot, reason, annulled_at)
                SELECT ?, ?, ?, a.crew_entry_id, (to_jsonb(a) || jsonb_build_object('gates',
                    COALESCE((SELECT jsonb_agg(to_jsonb(g)) FROM slalom_gate_penalties g WHERE g.attempt_result_id = a.id), '[]'::jsonb)))::text, ?, CURRENT_TIMESTAMP
                FROM attempt_results a JOIN crew_entries c ON c.id = a.crew_entry_id
                WHERE a.discipline_id = ? AND c.competition_category_id = ? AND a.crew_entry_id = ? AND a.attempt_number = ? AND a.version = ?
                """, competitionId, disciplineId, categoryId, reason.trim(), disciplineId, categoryId, crewId, attemptNumber, version);
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "no results to annul");
        jdbc.update("DELETE FROM slalom_gate_penalties WHERE attempt_result_id IN (SELECT id FROM attempt_results WHERE discipline_id = ? AND crew_entry_id = ? AND attempt_number = ?)", disciplineId, crewId, attemptNumber);
        jdbc.update("DELETE FROM attempt_results WHERE discipline_id = ? AND crew_entry_id = ? AND attempt_number = ?", disciplineId, crewId, attemptNumber);
        recalculation.recalculateLocked(discipline, categoryId);
    }
    private Discipline selection(long competitionId, long disciplineId, long categoryId, boolean lock) {
        var discipline = (lock ? disciplines.findForStartListUpdate(disciplineId) : disciplines.findById(disciplineId))
                .filter(d -> d.getCompetition().getId().equals(competitionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (discipline.getCategories().stream().noneMatch(c -> c.getId().equals(categoryId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return discipline;
    }

    private View view(StartList list) {
        return new View(list.getId(), slots.findByStartListIdOrderByPositionAsc(list.getId()).stream()
                .filter(s -> eligibility.isEligible(s.getCrew()))
                .map(s -> new Slot(s.getCrew().getId(), s.getCrew().getCrewName(), s.getCrew().getStartNumber(),
                        s.getPosition(), s.getPlannedTime())).toList(), hasResults(list.getDiscipline().getId(), list.getCategory().getId()));
    }
}
