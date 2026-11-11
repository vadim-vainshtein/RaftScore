package kz.vainshtein.raftscore.discipline.controllers;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.discipline.entities.DisciplineRepository;
import kz.vainshtein.raftscore.scoring.entities.PlacementPointsTableRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines")
@RequiredArgsConstructor
class DisciplineController {
    private final DisciplineRepository disciplines;
    private final PlacementPointsTableRepository tables;

    record UpdateRequest(
            @NotNull @DecimalMin("0") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, minimum = "0") BigDecimal displayOrder,
            @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, nullable = true, description = "Table ID, or null to unset") BigDecimal pointsTableId,
            @NotNull @DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, minimum = "0") BigDecimal version) { }
    record DisciplineResponse(long id, long competitionId, int displayOrder, Long pointsTableId, long version) { }

    @Operation(summary = "Update discipline order and points table", description = "Requires FULL_ACCESS and CSRF. Supply the current version and a table owned by this competition; null or omitted pointsTableId unsets it. A table can be assigned to only one discipline.")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DisciplineResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Discipline or points table not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Stale version or table already assigned", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{disciplineId}")
    @Transactional
    DisciplineResponse update(@PathVariable Long competitionId, @PathVariable Long disciplineId,
                              @Valid @RequestBody UpdateRequest request) {
        var discipline = disciplines.findById(disciplineId).filter(d -> d.getCompetition().getId().equals(competitionId))
                .orElseThrow(NotFound::new);
        if (discipline.getVersion() != request.version().longValueExact()) {
            throw new OptimisticLockingFailureException("Stale discipline version");
        }
        var table = request.pointsTableId() == null ? null : tables.findById(request.pointsTableId().longValueExact())
                .filter(t -> t.getCompetition().getId().equals(competitionId)).orElseThrow(NotFound::new);
        discipline.setDisplayOrder(request.displayOrder().intValueExact());
        discipline.setPointsTable(table);
        disciplines.saveAndFlush(discipline);
        return new DisciplineResponse(discipline.getId(), competitionId, discipline.getDisplayOrder(),
                table == null ? null : table.getId(), discipline.getVersion());
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ApiError> notFound() { return ResponseEntity.status(404).body(ApiError.of("discipline_or_points_table.not_found")); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformedPath() { return ResponseEntity.badRequest().body(ApiError.of("request.malformed")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrityViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "disciplines_points_table_uk".equals(violation.getConstraintName())) {
                return ResponseEntity.status(409).body(ApiError.of("discipline.points_table_assigned"));
            }
        }
        throw exception;
    }
    private static class NotFound extends RuntimeException { }
}
