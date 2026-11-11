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

    public record DisciplineResult(long disciplineId, String disciplineName, Integer place,
                                   CalculatedResultStatus status, int points) { }
    public record Standing(long crewId, String crewName, String regionOrClub, int startNumber,
                           Integer place, long totalPoints, boolean disqualified,
                           List<DisciplineResult> disciplines) { }

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
                    null, total, disqualified, breakdown));
        }
        standings.sort(Comparator.comparing(Standing::disqualified).thenComparing(Standing::totalPoints, Comparator.reverseOrder())
                .thenComparing(Standing::crewId));
        var ranked = new ArrayList<Standing>();
        long previousPoints = -1;
        int place = 0;
        for (var standing : standings) {
            if (!standing.disqualified()) {
                if (standing.totalPoints() != previousPoints) place = ranked.size() + 1;
                previousPoints = standing.totalPoints();
            }
            ranked.add(new Standing(standing.crewId(), standing.crewName(), standing.regionOrClub(), standing.startNumber(),
                    standing.disqualified() ? null : place, standing.totalPoints(), standing.disqualified(), standing.disciplines()));
        }
        return List.copyOf(ranked);
    }
}
