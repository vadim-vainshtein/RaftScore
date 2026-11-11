package kz.vainshtein.raftscore.standings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes current category standings from the available calculated results. */
@Service
@RequiredArgsConstructor
public class OverallStandingsService {
    private final CalculatedDisciplineResultRepository results;
    private final kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository categories;

    public record DisciplineResult(long disciplineId, String disciplineName, Integer place,
                                   CalculatedResultStatus status, int points) { }
    public record Standing(long crewId, String crewName, String regionOrClub, int startNumber,
                           Integer place, long totalPoints, boolean disqualified,
                           List<DisciplineResult> disciplines, Long appliedTiebreakDisciplineId) { }

    @Transactional(readOnly = true)
    public List<Standing> standings(Long categoryId) {
        var crews = new LinkedHashMap<Long, List<kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResult>>();
        for (var result : results.findCurrentByCategoryId(categoryId)) {
            crews.computeIfAbsent(result.getCrewEntry().getId(), ignored -> new ArrayList<>()).add(result);
        }
        var standings = new ArrayList<Standing>();
        for (var rows : crews.values()) {
            var crew = rows.getFirst().getCrewEntry();
            var total = rows.stream().mapToLong(row -> row.getPoints()).sum();
            var disqualified = rows.stream().anyMatch(row -> row.getStatus() == CalculatedResultStatus.DSQ_COMPETITION);
            var breakdown = rows.stream().map(row -> new DisciplineResult(row.getDiscipline().getId(),
                    row.getDiscipline().getName(), row.getPlace(), row.getStatus(), row.getPoints())).toList();
            standings.add(new Standing(crew.getId(), crew.getCrewName(), crew.getRegionOrClub(), crew.getStartNumber(),
                    null, total, disqualified, breakdown, null));
        }
        var category = categories.findById(categoryId).orElseThrow();
        var selected = category.getCompetition().getTiebreakDiscipline();
        Long selectedId = selected == null ? null : selected.getId();
        standings.sort(Comparator.comparing(Standing::disqualified).thenComparing(Standing::totalPoints, Comparator.reverseOrder())
                .thenComparing(Standing::crewId));
        var ranked = new ArrayList<Standing>();
        for (int first = 0; first < standings.size();) {
            int end = first + 1;
            var leader = standings.get(first);
            while (end < standings.size() && standings.get(end).disqualified() == leader.disqualified()
                    && standings.get(end).totalPoints() == leader.totalPoints()) end++;
            var group = new ArrayList<>(standings.subList(first, end));
            boolean resolve = !leader.disqualified() && selectedId != null
                    && group.stream().allMatch(standing -> tiebreakPlace(standing, selectedId) != null);
            if (resolve) {
                group.sort(Comparator.comparing((Standing standing) -> tiebreakPlace(standing, selectedId))
                        .thenComparing(Standing::crewId));
            }
            boolean applied = resolve && group.size() > 1
                    && group.stream().map(standing -> tiebreakPlace(standing, selectedId)).distinct().count() > 1;
            Integer previous = null;
            int place = first + 1;
            for (int index = 0; index < group.size(); index++) {
                var standing = group.get(index);
                var current = resolve ? tiebreakPlace(standing, selectedId) : null;
                if (resolve && index > 0 && !current.equals(previous)) place = first + index + 1;
                previous = current;
                ranked.add(new Standing(standing.crewId(), standing.crewName(), standing.regionOrClub(), standing.startNumber(),
                        standing.disqualified() ? null : place, standing.totalPoints(), standing.disqualified(), standing.disciplines(), applied ? selectedId : null));
            }
            first = end;
        }
        return List.copyOf(ranked);
    }

    private Integer tiebreakPlace(Standing standing, Long disciplineId) {
        return standing.disciplines().stream()
                .filter(result -> result.disciplineId() == disciplineId && result.status() == CalculatedResultStatus.FINISHED)
                .map(DisciplineResult::place).filter(java.util.Objects::nonNull).filter(place -> place > 0)
                .findFirst().orElse(null);
    }
}
