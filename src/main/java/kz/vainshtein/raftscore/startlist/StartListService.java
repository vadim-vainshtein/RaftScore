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

    public record Slot(long crewId, String crewName, int startNumber, int position, Instant plannedTime) { }
    public record View(Long id, List<Slot> slots, boolean locked) { }

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
    public void clearResults(long competitionId, long disciplineId, long categoryId) {
        selection(competitionId, disciplineId, categoryId, true);
        jdbc.update("DELETE FROM slalom_gate_penalties WHERE attempt_result_id IN (SELECT a.id FROM attempt_results a JOIN crew_entries c ON c.id = a.crew_entry_id WHERE a.discipline_id = ? AND c.competition_category_id = ?)", disciplineId, categoryId);
        jdbc.update("DELETE FROM attempt_results WHERE discipline_id = ? AND crew_entry_id IN (SELECT id FROM crew_entries WHERE competition_category_id = ?)", disciplineId, categoryId);
        jdbc.update("DELETE FROM calculated_discipline_results WHERE discipline_id = ? AND crew_entry_id IN (SELECT id FROM crew_entries WHERE competition_category_id = ?)", disciplineId, categoryId);
        // A played bracket contains propagated winners, so discard it and require reseeding.
        var played = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM parallel_sprint_heats h JOIN parallel_sprint_brackets b ON b.id = h.bracket_id WHERE b.discipline_id = ? AND b.category_id = ? AND (h.status_1 IS NOT NULL OR h.status_2 IS NOT NULL OR h.winner_overridden))", Boolean.class, disciplineId, categoryId));
        if (played) {
            jdbc.update("DELETE FROM parallel_sprint_heats WHERE bracket_id IN (SELECT id FROM parallel_sprint_brackets WHERE discipline_id = ? AND category_id = ?)", disciplineId, categoryId);
            jdbc.update("DELETE FROM parallel_sprint_brackets WHERE discipline_id = ? AND category_id = ?", disciplineId, categoryId);
        }
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
                .map(s -> new Slot(s.getCrew().getId(), s.getCrew().getCrewName(), s.getCrew().getStartNumber(),
                        s.getPosition(), s.getPlannedTime())).toList(), hasResults(list.getDiscipline().getId(), list.getCategory().getId()));
    }
}
