package kz.vainshtein.raftscore.scoring;

import java.util.Map;
import java.util.stream.Collectors;

import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus;
import kz.vainshtein.raftscore.scoring.entities.PlacementPointsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assigns points from the current discipline table to one category's calculated results. */
@Service
@RequiredArgsConstructor
public class PlacementPointsAssignmentService {
    private final DisciplineRepository disciplines;
    private final CalculatedDisciplineResultRepository results;

    @Transactional
    public void assign(Long disciplineId, Long categoryId) {
        var discipline = disciplines.findById(disciplineId).orElseThrow();
        var table = discipline.getPointsTable();
        Map<Integer, Integer> pointsByPlace = table == null ? Map.of() : table.getRows().stream()
                .collect(Collectors.toMap(PlacementPointsRow::getPlace, PlacementPointsRow::getPoints));
        for (var result : results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, categoryId)) {
            var points = result.getStatus() == CalculatedResultStatus.FINISHED && result.getPlace() != null
                    ? pointsByPlace.getOrDefault(result.getPlace(), 0) : 0;
            result.updateResult(result.getPlace(), result.getTotalCentiseconds(), result.getSummary(), result.getStatus(), points);
        }
    }
}
