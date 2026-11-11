package kz.vainshtein.raftscore.scoring;

import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/recalculate")
@RequiredArgsConstructor
class RecalculationController {
    private final CompetitionRepository competitions;
    private final RecalculationService recalculation;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recalculate(@PathVariable long competitionId) {
        if (!competitions.existsById(competitionId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        recalculation.recalculateCompetition(competitionId);
    }
}
