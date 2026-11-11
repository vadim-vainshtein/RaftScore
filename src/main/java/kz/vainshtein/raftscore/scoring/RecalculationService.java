package kz.vainshtein.raftscore.scoring;

import java.util.*;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracketRepository;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.startlist.entities.StartListRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Rebuilds persisted standings under the discipline lock used by source writers. */
@Service
@RequiredArgsConstructor
public class RecalculationService {
    private final DisciplineRepository disciplines;
    private final StartListRepository lists;
    private final ParallelSprintBracketRepository brackets;
    private final CalculatedDisciplineResultRepository results;
    private final SprintRankingService sprint;
    private final LongRaceRankingService longRace;
    private final SlalomRankingService slalom;
    private final ParallelSprintRankingService parallel;
    private final PlacementPointsAssignmentService points;
    private final kz.vainshtein.raftscore.timing.entities.AttemptResultRepository attempts;

    private record Row(CrewEntry crew, AttemptResultStatus status, Long total, Integer place) { }

    @Transactional
    public void recalculateCompetition(long competitionId) {
        var ids = disciplines.findIdsByCompetitionId(competitionId);
        // Lock every discipline before recalculating any of them, in a stable order.
        var locked = ids.stream().map(id -> disciplines.findForStartListUpdate(id).orElseThrow()).toList();
        for (var discipline : locked) recalculateDisciplineLocked(discipline);
    }

    @Transactional
    public void recalculateDisciplineLocked(Discipline discipline) {
        for (var category : discipline.getCategories()) recalculateLocked(discipline, category.getId());
    }

    @Transactional
    public void recalculateLocked(Discipline discipline, long categoryId) {
        List<Row> rows;
        if (discipline.getType() == DisciplineType.PARALLEL_SPRINT) {
            rows = brackets.findByDisciplineIdAndCategoryId(discipline.getId(), categoryId)
                    .map(b -> parallel.rank(b.getId()).stream().map(r -> new Row(r.crew(), r.status(), null, r.place())).toList())
                    .orElseGet(List::of);
        } else {
            var list = lists.findByDisciplineIdAndCategoryId(discipline.getId(), categoryId);
            if (list.isEmpty() && !attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), categoryId).isEmpty())
                throw new IllegalArgumentException("attempts require a start list");
            rows = list.map(l -> switch (discipline.getType()) {
                case SPRINT -> sprint.rank(l.getId()).stream().map(r -> new Row(r.crew(), r.status(), r.totalCentiseconds(), r.place())).toList();
                case LONG_RACE -> longRace.rank(l.getId()).stream().map(r -> new Row(r.crew(), r.status(), r.totalCentiseconds(), r.place())).toList();
                case SLALOM -> slalom.rank(l.getId()).stream().map(r -> new Row(r.crew(), r.status(), r.totalCentiseconds(), r.place())).toList();
                default -> throw new IllegalStateException("unsupported discipline");
            }).orElseGet(List::of);
        }
        var existing = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), categoryId);
        var byCrew = new HashMap<Long, CalculatedDisciplineResult>();
        existing.forEach(r -> byCrew.put(r.getCrewEntry().getId(), r));
        for (var row : rows) {
            var status = CalculatedResultStatus.valueOf(row.status().name());
            var result = byCrew.remove(row.crew().getId());
            if (result == null) {
                result = CalculatedDisciplineResult.builder().discipline(discipline).crewEntry(row.crew())
                        .place(row.place()).totalCentiseconds(row.total()).status(status).points(0).build();
                results.save(result);
            } else result.updateResult(row.place(), row.total(), null, status, result.getPoints());
        }
        results.deleteAll(byCrew.values());
        results.flush();
        points.assign(discipline.getId(), categoryId);
    }
}
