package kz.vainshtein.raftscore.startlist;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines/{disciplineId}/categories/{categoryId}/start-list")
@RequiredArgsConstructor
class StartListController {
    private final StartListService service;
    record ReplaceRequest(@NotNull Boolean random, List<@NotNull @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal> crewIds, Instant firstStart, @DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal intervalSeconds) { }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    org.springframework.http.ResponseEntity<kz.vainshtein.raftscore.api.ApiError> error(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(kz.vainshtein.raftscore.api.ApiError.of("start_list.invalid"));
    }

    @GetMapping
    StartListService.View read(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId) {
        return service.read(competitionId, disciplineId, categoryId);
    }

    @PutMapping
    StartListService.View replace(@PathVariable long competitionId, @PathVariable long disciplineId, @PathVariable long categoryId,
                                  @Valid @RequestBody ReplaceRequest request) {
        return service.replace(competitionId, disciplineId, categoryId, request.crewIds() == null ? null : request.crewIds().stream().map(BigDecimal::longValueExact).toList(), request.random(),
                request.firstStart(), request.intervalSeconds() == null ? null : request.intervalSeconds().longValueExact());
    }
}
