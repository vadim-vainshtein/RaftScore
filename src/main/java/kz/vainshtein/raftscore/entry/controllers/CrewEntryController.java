package kz.vainshtein.raftscore.entry.controllers;

import java.math.BigDecimal;
import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/competitions/{competitionId}/entries")
@RequiredArgsConstructor
class CrewEntryController {
    private final CompetitionRepository competitions;
    private final CompetitionCategoryRepository categories;
    private final CrewEntryRepository entries;

    record CreateRequest(
            @NotNull @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal categoryId,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber) { }

    record UpdateRequest(
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber,
            @NotNull @PositiveOrZero @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version) { }

    record EntryResponse(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub,
                         String representative, int startNumber, long version) {
        static EntryResponse from(CrewEntry entry) {
            var category = entry.getCompetitionCategory();
            return new EntryResponse(entry.getId(), category.getCompetition().getId(), category.getId(),
                    entry.getCrewName(), entry.getRegionOrClub(), entry.getRepresentative(), entry.getStartNumber(), entry.getVersion());
        }
    }

    @Operation(summary = "Create a crew entry", description = "Requires FULL_ACCESS and a CSRF token.")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Competition or category not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Start number already exists in category", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @Transactional
    ResponseEntity<EntryResponse> create(@PathVariable Long competitionId, @Valid @RequestBody CreateRequest request) {
        if (!competitions.existsById(competitionId)) throw new CompetitionNotFound();
        var category = categories.findByIdAndCompetitionId(request.categoryId().longValueExact(), competitionId).orElseThrow(CategoryNotFound::new);
        var entry = entries.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName(request.crewName())
                .regionOrClub(request.regionOrClub()).representative(request.representative()).startNumber(request.startNumber().intValueExact()).build());
        return ResponseEntity.created(URI.create("/api/competitions/" + competitionId + "/entries/" + entry.getId())).body(EntryResponse.from(entry));
    }

    @Operation(summary = "Update crew entry metadata", description = "Requires FULL_ACCESS, a CSRF token and the current version. Category cannot be changed.")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content(schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Entry not found in competition", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Stale version or start number already exists in category", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{entryId}")
    @Transactional
    EntryResponse update(@PathVariable Long competitionId, @PathVariable Long entryId, @Valid @RequestBody UpdateRequest request) {
        var entry = entries.findByIdAndCompetitionCategoryCompetitionId(entryId, competitionId).orElseThrow(EntryNotFound::new);
        if (entry.getVersion() != request.version().longValueExact()) throw new OptimisticLockingFailureException("Stale entry version");
        entry.updateDetails(request.crewName(), request.regionOrClub(), request.representative(), request.startNumber().intValueExact());
        entries.flush();
        return EntryResponse.from(entry);
    }

    @ExceptionHandler(CompetitionNotFound.class)
    ResponseEntity<ApiError> competitionNotFound() { return ResponseEntity.status(404).body(ApiError.of("competition.not_found")); }
    @ExceptionHandler(CategoryNotFound.class)
    ResponseEntity<ApiError> categoryNotFound() { return ResponseEntity.status(404).body(ApiError.of("competition_category.not_found")); }
    @ExceptionHandler(EntryNotFound.class)
    ResponseEntity<ApiError> entryNotFound() { return ResponseEntity.status(404).body(ApiError.of("crew_entry.not_found")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrityViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "crew_entries_category_start_number_uk".equals(violation.getConstraintName())) {
                return ResponseEntity.status(409).body(ApiError.of("crew_entry.start_number_duplicate"));
            }
        }
        throw exception;
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformedPath() { return ResponseEntity.badRequest().body(ApiError.of("request.malformed")); }
    private static class CompetitionNotFound extends RuntimeException { }
    private static class CategoryNotFound extends RuntimeException { }
    private static class EntryNotFound extends RuntimeException { }
}
