package kz.vainshtein.raftscore.timing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import kz.vainshtein.raftscore.scoring.RecalculationService;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/crews/{crewId}/attempts")
@RequiredArgsConstructor
class AttemptResultController {
    private final jakarta.persistence.EntityManager entityManager;
    private final DisciplineRepository disciplines;
    private final CrewEntryRepository crews;
    private final StartListRepository lists;
    private final StartSlotRepository slots;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository configurations;
    private final SlalomGatePenaltyRepository penalties;
    private final RecalculationService recalculation;

    record Gate(@NotNull @DecimalMin("1") @Digits(integer=10, fraction=0) BigDecimal number,
                @NotNull @DecimalMin("0") @Digits(integer=10, fraction=0) BigDecimal penaltySeconds) { }
    record Request(@DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer=19, fraction=0) BigDecimal version,
                   @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer=10, fraction=0) BigDecimal durationCentiseconds,
                   Instant startInstant, Instant finishInstant,
                   @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer=10, fraction=0) BigDecimal penaltyCentiseconds,
                   @NotNull AttemptResultStatus status, @Valid List<@NotNull Gate> gates) { }
    record View(long id, long version) { }

    @PutMapping("/{attemptNumber}")
    @Transactional
    View put(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId,
             @PathVariable long crewId, @PathVariable int attemptNumber, @Valid @RequestBody Request request) {
        var discipline = disciplines.findForStartListUpdate(disciplineId)
                .filter(d -> d.getCompetition().getId().equals(competitionId)
                        && d.getCategories().stream().anyMatch(c -> c.getId().equals(categoryId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var crew = crews.findByIdAndCompetitionCategoryCompetitionId(crewId, competitionId)
                .filter(c -> c.getCompetitionCategory().getId().equals(categoryId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var list = lists.findByDisciplineIdAndCategoryId(disciplineId, categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (discipline.getType() == DisciplineType.PARALLEL_SPRINT || slots.findByStartListIdOrderByPositionAsc(list.getId()).stream().noneMatch(s -> s.getCrew().getId().equals(crewId)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "crew must have a start slot in a timed discipline");
        try {
            var existing = attempts.findByDisciplineIdAndCrewEntryIdAndAttemptNumber(disciplineId, crewId, attemptNumber);
            var duration = request.durationCentiseconds() == null ? null : request.durationCentiseconds().intValueExact();
            var penalty = request.penaltyCentiseconds().intValueExact();
            if (existing.isPresent() && (request.version() == null || existing.get().getVersion() != request.version().longValueExact())) {
                var saved = existing.get();
                boolean immediate = request.version() == null ? saved.getVersion() == 0
                        : request.version().longValueExact() < Long.MAX_VALUE && saved.getVersion() == request.version().longValueExact() + 1;
                if (immediate && matches(saved, discipline, crew, attemptNumber, duration, penalty, request))
                    return new View(saved.getId(), saved.getVersion());
                throw new OptimisticLockingFailureException("stale attempt version");
            }
            if (existing.isEmpty() && request.version() != null) throw new OptimisticLockingFailureException("stale attempt version");
            var attempt = existing.orElseGet(() -> attempts.save(AttemptResult.builder().discipline(discipline).crewEntry(crew)
                    .attemptNumber(attemptNumber).durationCentiseconds(duration).startInstant(request.startInstant())
                    .finishInstant(request.finishInstant()).penaltyCentiseconds(penalty).status(request.status()).build()));
            if (existing.isPresent()) attempt.updateResult(duration, request.startInstant(), request.finishInstant(), penalty, request.status());
            if (discipline.getType() == DisciplineType.SLALOM) {
                var config = configurations.findByDisciplineId(disciplineId).orElseThrow(() -> new IllegalArgumentException("slalom configuration required"));
                penalties.deleteByAttemptId(attempt.getId());
                penalties.flush();
                var seen = new java.util.HashSet<Integer>();
                if (request.gates() != null) for (var gate : request.gates()) {
                    var number = gate.number().intValueExact();
                    if (!seen.add(number)) throw new IllegalArgumentException("duplicate gate number");
                    penalties.save(new SlalomGatePenalty(attempt, config, number, gate.penaltySeconds().intValueExact()));
                }
            } else if (request.gates() != null && !request.gates().isEmpty()) throw new IllegalArgumentException("gates require slalom");
            attempts.flush();
            // Increment the parent version for gate-only changes, once per update.
            if (existing.isPresent() && attempt.getVersion() == request.version().longValueExact()) {
                entityManager.lock(attempt, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
            penalties.flush();
            recalculation.recalculateLocked(discipline, categoryId);
            return new View(attempt.getId(), attempt.getVersion());
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
    private boolean matches(AttemptResult saved, Discipline discipline, kz.vainshtein.raftscore.entry.entities.CrewEntry crew,
                            int attemptNumber, Integer duration, int penalty, Request request) {
        var candidate = AttemptResult.builder().discipline(discipline).crewEntry(crew).attemptNumber(attemptNumber)
                .durationCentiseconds(duration).startInstant(request.startInstant()).finishInstant(request.finishInstant())
                .penaltyCentiseconds(penalty).status(request.status()).build();
        if (!Objects.equals(saved.getDurationCentiseconds(), candidate.getDurationCentiseconds())
                || !Objects.equals(saved.getStartInstant(), candidate.getStartInstant())
                || !Objects.equals(saved.getFinishInstant(), candidate.getFinishInstant())
                || saved.getPenaltyCentiseconds() != candidate.getPenaltyCentiseconds() || saved.getStatus() != candidate.getStatus()) return false;
        if (discipline.getType() != DisciplineType.SLALOM) return request.gates() == null || request.gates().isEmpty();
        var config = configurations.findByDisciplineId(discipline.getId()).orElseThrow();
        var submitted = new java.util.HashMap<Integer, Integer>();
        if (request.gates() != null) for (var gate : request.gates()) {
            var number = gate.number().intValueExact();
            var value = gate.penaltySeconds().intValueExact();
            new SlalomGatePenalty(saved, config, number, value);
            if (submitted.put(number, value) != null) throw new IllegalArgumentException("duplicate gate number");
        }
        var stored = penalties.findByAttemptId(saved.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(SlalomGatePenalty::getGateNumber, SlalomGatePenalty::getPenaltySeconds));
        return stored.equals(submitted);
    }

}
