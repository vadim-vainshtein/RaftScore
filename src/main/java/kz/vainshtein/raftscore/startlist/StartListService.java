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
    private final StartListRepository lists;
    private final StartSlotRepository slots;

    public record Slot(long crewId, String crewName, int startNumber, int position, Instant plannedTime) { }
    public record View(Long id, List<Slot> slots) { }

    @Transactional(readOnly = true)
    public View read(long competitionId, long disciplineId, long categoryId) {
        selection(competitionId, disciplineId, categoryId, false);
        return lists.findByDisciplineIdAndCategoryId(disciplineId, categoryId)
                .map(this::view).orElseGet(() -> new View(null, List.of()));
    }

    /** Serializes replacements on the discipline, including initial list creation. */
    @Transactional
    public View replace(long competitionId, long disciplineId, long categoryId, List<Long> order,
                        boolean random, Instant firstStart, Long intervalSeconds) {
        var discipline = selection(competitionId, disciplineId, categoryId, true);
        if ((firstStart == null) != (intervalSeconds == null) || (intervalSeconds != null && intervalSeconds < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "firstStart and non-negative intervalSeconds must be supplied together");
        var eligible = crews.findByCompetitionCategoryId(categoryId).stream().filter(eligibility::isEligible).toList();
        var byId = eligible.stream().collect(java.util.stream.Collectors.toMap(c -> c.getId(), c -> c));
        var ids = eligible.stream().map(c -> c.getId()).toList();
        List<Long> selected;
        if (random) {
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
                        s.getPosition(), s.getPlannedTime())).toList());
    }
}
