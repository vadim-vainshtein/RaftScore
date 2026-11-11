package kz.vainshtein.raftscore.bracket;

import java.util.List;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracketRepository;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/bracket")
@RequiredArgsConstructor
class CurrentParallelSprintBracketController {
    private final DisciplineRepository disciplines;
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;
    private final ParallelSprintSeedingService seeding;

    record Bracket(Long id, List<ParallelSprintBracketController.Heat> heats) { }

    @GetMapping
    @Transactional(readOnly = true)
    Bracket current(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId) {
        checkScope(competitionId, disciplineId, categoryId);
        return brackets.findByDisciplineIdAndCategoryId(disciplineId, categoryId)
                .map(b -> new Bracket(b.getId(), heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(b.getId())
                        .stream().map(ParallelSprintBracketController::view).toList()))
                .orElseGet(() -> new Bracket(null, List.of()));
    }

    @PostMapping
    @Transactional
    Bracket seed(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId) {
        checkScope(competitionId, disciplineId, categoryId);
        try {
            var bracket = seeding.seed(disciplineId, categoryId, null);
            return new Bracket(bracket.getId(), heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId())
                    .stream().map(ParallelSprintBracketController::view).toList());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private void checkScope(long competitionId, long disciplineId, long categoryId) {
        disciplines.findById(disciplineId).filter(d -> d.getType() == DisciplineType.PARALLEL_SPRINT
                && d.getCompetition().getId().equals(competitionId)
                && d.getCategories().stream().anyMatch(c -> c.getId().equals(categoryId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
