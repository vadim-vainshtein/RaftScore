package kz.vainshtein.raftscore.timing;

import java.time.Instant;
import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfigurationRepository;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenaltyRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/attempt-grid")
@RequiredArgsConstructor
class AttemptGridController {
    private final DisciplineRepository disciplines;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository configurations;
    private final SlalomGatePenaltyRepository penalties;
    private final CalculatedDisciplineResultRepository calculatedResults;

    record Gate(int number, int penaltySeconds) { }
    record View(long crewId, int attemptNumber, long version, Integer durationCentiseconds,
                Instant startInstant, Instant finishInstant, int penaltyCentiseconds,
                AttemptResultStatus status, List<Gate> gates) { }

    record Standing(long crewId, Integer place, Long totalCentiseconds, CalculatedResultStatus status) { }
    record GridView(List<View> attempts, List<Standing> standings) { }

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    GridView read(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId) {
        var discipline = disciplines.findById(disciplineId)
                .filter(d -> d.getCompetition().getId().equals(competitionId)
                        && d.getCategories().stream().anyMatch(c -> c.getId().equals(categoryId))
                        && d.getType() != DisciplineType.PARALLEL_SPRINT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var gates = discipline.getType() == DisciplineType.SLALOM
                ? configurations.findByDisciplineId(disciplineId)
                    .map(c -> penalties.findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(c.getId(), categoryId))
                    .orElse(List.of()) : List.<kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty>of();
        var attemptViews = attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, categoryId).stream()
                .map(a -> new View(a.getCrewEntry().getId(), a.getAttemptNumber(), a.getVersion(),
                        a.getDurationCentiseconds(), a.getStartInstant(), a.getFinishInstant(), a.getPenaltyCentiseconds(), a.getStatus(),
                        gates.stream().filter(g -> g.getAttempt().getId().equals(a.getId()))
                                .map(g -> new Gate(g.getGateNumber(), g.getPenaltySeconds())).toList()))
                .toList();
        var standings = calculatedResults.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, categoryId).stream()
                .map(r -> new Standing(r.getCrewEntry().getId(), r.getPlace(), r.getTotalCentiseconds(), r.getStatus()))
                .toList();
        return new GridView(attemptViews, standings);
    }
}
