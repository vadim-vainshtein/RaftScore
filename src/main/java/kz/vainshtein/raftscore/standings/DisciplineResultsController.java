package kz.vainshtein.raftscore.standings;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import kz.vainshtein.raftscore.timing.entities.AttemptResultRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfigurationRepository;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenaltyRepository;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository;
import kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/results")
@RequiredArgsConstructor
class DisciplineResultsController {
    private final DisciplineRepository disciplines;
    private final CalculatedDisciplineResultRepository results;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository configurations;
    private final SlalomGatePenaltyRepository penalties;
    record Gate(int number, int penaltySeconds) { }
    record Attempt(int number, Integer durationCentiseconds, int penaltyCentiseconds, AttemptResultStatus status, List<Gate> gates) { }
    record Result(long crewId, String crewName, String regionOrClub, int startNumber,
                  Integer place, Long totalCentiseconds, CalculatedResultStatus status, int points, List<Attempt> attempts) { }
    @GetMapping
    @Transactional(readOnly = true)
    List<Result> get(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId) {
        disciplines.findById(disciplineId).filter(d -> d.getCompetition().getId().equals(competitionId)
                && d.getCategories().stream().anyMatch(c -> c.getId().equals(categoryId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var savedAttempts = attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, categoryId);
        var gates = configurations.findByDisciplineId(disciplineId)
                .map(c -> penalties.findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(c.getId(), categoryId))
                .orElse(List.of());
        var gatesByAttempt = gates.stream().collect(Collectors.groupingBy(g -> g.getAttempt().getId(),
                Collectors.mapping(g -> new Gate(g.getGateNumber(), g.getPenaltySeconds()), Collectors.toList())));
        var attemptsByCrew = savedAttempts.stream().collect(Collectors.groupingBy(a -> a.getCrewEntry().getId(),
                Collectors.mapping(a -> new Attempt(a.getAttemptNumber(), a.getDurationCentiseconds(),
                        a.getPenaltyCentiseconds(), a.getStatus(), gatesByAttempt.getOrDefault(a.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(Gate::number)).toList()), Collectors.toList())));
        return results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, categoryId).stream()
                .map(r -> new Result(r.getCrewEntry().getId(), r.getCrewEntry().getCrewName(),
                        r.getCrewEntry().getRegionOrClub(), r.getCrewEntry().getStartNumber(), r.getPlace(),
                        r.getTotalCentiseconds(), r.getStatus(), r.getPoints(),
                        attemptsByCrew.getOrDefault(r.getCrewEntry().getId(), List.of()).stream()
                                .sorted(Comparator.comparingInt(Attempt::number)).toList()))
                .sorted(Comparator.comparing(Result::place, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Result::crewId)).toList();
    }
}
