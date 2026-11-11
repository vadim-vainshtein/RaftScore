package kz.vainshtein.raftscore.bracket;

import java.util.List;
import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracketRepository;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/brackets/{bracketId}")
@RequiredArgsConstructor
class ParallelSprintBracketController {
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;
    private final ParallelSprintResultService results;

    record Crew(long id, String name, int startNumber) { }
    record Heat(long id, long version, int round, int number, Crew first, Crew second,
                AttemptResultStatus firstStatus, Integer firstTimeCentiseconds,
                AttemptResultStatus secondStatus, Integer secondTimeCentiseconds,
                Integer winnerSlot, boolean winnerOverridden, Long nextHeatId, Integer nextSlot) { }
    record ResultInput(@NotNull AttemptResultStatus status, @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal timeCentiseconds) { }
    record ResultRequest(@NotNull @DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal version, @NotNull @Valid ResultInput first,
                         @NotNull @Valid ResultInput second, @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal winnerCrewId, Boolean confirmed) { }

    @GetMapping
    @Transactional(readOnly = true)
    List<Heat> read(@PathVariable long competitionId, @PathVariable long disciplineId,
                    @PathVariable long categoryId, @PathVariable long bracketId) {
        checkScope(competitionId, disciplineId, categoryId, bracketId);
        return heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracketId).stream().map(this::view).toList();
    }

    @PutMapping("/heats/{heatId}/result")
    @Transactional
    Heat record(@PathVariable long competitionId, @PathVariable long disciplineId,
                @PathVariable long categoryId, @PathVariable long bracketId, @PathVariable long heatId,
                @Valid @RequestBody ResultRequest request) {
        checkScope(competitionId, disciplineId, categoryId, bracketId);
        var heat = heats.findById(heatId).filter(h -> h.getBracket().getId().equals(bracketId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (request.winnerCrewId() != null && !Boolean.TRUE.equals(request.confirmed()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "winner override requires confirmation");
        try {
            results.record(heatId, request.version().longValueExact(), result(request.first()), result(request.second()), request.winnerCrewId() == null ? null : request.winnerCrewId().longValueExact());
            return view(heat);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private ParallelSprintResultService.Result result(ResultInput input) {
        return new ParallelSprintResultService.Result(input.status(), input.timeCentiseconds() == null ? null : input.timeCentiseconds().intValueExact());
    }

    private void checkScope(long competitionId, long disciplineId, long categoryId, long bracketId) {
        brackets.findById(bracketId).filter(b -> b.getDiscipline().getId().equals(disciplineId)
                && b.getDiscipline().getCompetition().getId().equals(competitionId)
                && b.getCategory().getId().equals(categoryId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    Heat view(ParallelSprintHeat heat) {
        return new Heat(heat.getId(), heat.getVersion(), heat.getRoundNumber(), heat.getHeatNumber(),
                crew(heat.getCrew1()), crew(heat.getCrew2()), heat.getStatus1(), heat.getTime1Centiseconds(),
                heat.getStatus2(), heat.getTime2Centiseconds(), heat.getWinnerSlot(), heat.isWinnerOverridden(),
                heat.getNextHeat() == null ? null : heat.getNextHeat().getId(), heat.getNextSlot());
    }
    private Crew crew(CrewEntry crew) {
        return crew == null ? null : new Crew(crew.getId(), crew.getCrewName(), crew.getStartNumber());
    }
}
