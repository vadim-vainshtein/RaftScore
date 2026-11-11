package kz.vainshtein.raftscore.competition.controllers;

import java.math.BigDecimal;
import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Creates competition-owned snapshots of reusable category templates. */
@RestController
@RequestMapping("/api/competitions/{competitionId}/categories")
@RequiredArgsConstructor
class CompetitionCategoryController {
    private final CompetitionRepository competitions;
    private final CategoryTemplateRepository templates;
    private final CompetitionCategoryRepository categories;

    record CreateCategoryRequest(@NotNull @DecimalMin("1") @DecimalMax("9223372036854775807")
                                 @Digits(integer = 19, fraction = 0)
                                 @Schema(implementation = Long.class, type = "integer", format = "int64", minimum = "1")
                                 BigDecimal templateId) { }
    record CategorySnapshot(long id, long competitionId, String classLabel, String scoringGroupLabel,
                            int crewSize, String displayName, long version) {
        static CategorySnapshot from(CompetitionCategory category) {
            return new CategorySnapshot(category.getId(), category.getCompetition().getId(), category.getClassLabel(),
                    category.getScoringGroupLabel(), category.getCrewSize(), category.getDisplayName(), category.getVersion());
        }
    }

    @Operation(summary = "Create a category from an active template", description = "Requires FULL_ACCESS and a CSRF token. The category is an independent snapshot.")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = CategorySnapshot.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Competition or template not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Template is archived", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @Transactional
    ResponseEntity<CategorySnapshot> create(@PathVariable Long competitionId, @Valid @RequestBody CreateCategoryRequest request) {
        var competition = competitions.findById(competitionId).orElseThrow(CompetitionNotFound::new);
        var template = templates.findForSnapshot(request.templateId().longValueExact()).orElseThrow(TemplateNotFound::new);
        if (template.isArchived()) {
            throw new TemplateArchived();
        }
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel(template.getClassLabel()).scoringGroupLabel(template.getScoringGroupLabel())
                .crewSize(template.getCrewSize()).build());
        return ResponseEntity.created(URI.create("/api/competitions/" + competitionId + "/categories/" + category.getId()))
                .body(CategorySnapshot.from(category));
    }

    @ExceptionHandler(CompetitionNotFound.class)
    ResponseEntity<ApiError> competitionNotFound() { return ResponseEntity.status(404).body(ApiError.of("competition.not_found")); }
    @ExceptionHandler(TemplateNotFound.class)
    ResponseEntity<ApiError> templateNotFound() { return ResponseEntity.status(404).body(ApiError.of("category_template.not_found")); }
    @ExceptionHandler(TemplateArchived.class)
    ResponseEntity<ApiError> templateArchived() { return ResponseEntity.status(409).body(ApiError.of("category_template.archived")); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformedPath() { return ResponseEntity.badRequest().body(ApiError.of("request.malformed")); }

    private static class CompetitionNotFound extends RuntimeException { }
    private static class TemplateNotFound extends RuntimeException { }
    private static class TemplateArchived extends RuntimeException { }
}
