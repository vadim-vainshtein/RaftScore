package kz.vainshtein.raftscore.catalog.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;

import java.net.URI;
import java.math.BigDecimal;
import java.util.List;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplate;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Administration of reusable category templates. */
@RestController
@RequestMapping("/api/category-templates")
@RequiredArgsConstructor
class CategoryTemplateController {
    private final CategoryTemplateRepository repository;

    record Details(@NotBlank @Pattern(regexp = "[^\\x00]*") String classLabel, @NotBlank @Pattern(regexp = "[^\\x00]*") String scoringGroupLabel,
                   @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647") @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal crewSize) { }
    record Edit(@NotBlank @Pattern(regexp = "[^\\x00]*") String classLabel, @NotBlank @Pattern(regexp = "[^\\x00]*") String scoringGroupLabel,
                @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647") @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0) BigDecimal crewSize, @Schema(implementation = Long.class, type = "integer", format = "int64", minimum = "0", maximum = "9223372036854775807") @NotNull @DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal version) { }
    record Version(@Schema(implementation = Long.class, type = "integer", format = "int64", minimum = "0", maximum = "9223372036854775807") @NotNull @DecimalMin("0") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal version) { }
    record Template(long id, String classLabel, String scoringGroupLabel, int crewSize,
                    String displayName, boolean archived, long version) {
        static Template from(CategoryTemplate entity) {
            return new Template(entity.getId(), entity.getClassLabel(), entity.getScoringGroupLabel(),
                    entity.getCrewSize(), entity.getDisplayName(), entity.isArchived(), entity.getVersion());
        }
    }
    record TemplatePage(List<Template> content, int page, int size, long totalElements, int totalPages) { }

    @Operation(summary = "Create a category template", description = "Requires FULL_ACCESS and a CSRF token.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Template.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token")})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    @Transactional
    ResponseEntity<Template> create(@Valid @RequestBody Details request) {
        var entity = repository.saveAndFlush(CategoryTemplate.builder().classLabel(request.classLabel())
                .scoringGroupLabel(request.scoringGroupLabel()).crewSize(request.crewSize().intValueExact()).build());
        return ResponseEntity.created(URI.create("/api/category-templates/" + entity.getId())).body(Template.from(entity));
    }

    @Operation(summary = "List category templates", description = "Requires FULL_ACCESS. Omit archived to include all templates.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Page of templates", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TemplatePage.class))),
            @ApiResponse(responseCode = "400", description = "Invalid parameters", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "FULL_ACCESS required")})
    @GetMapping
    @Transactional(readOnly = true)
    TemplatePage list(@Parameter(description = "Zero-based page; page multiplied by size must not exceed 2147483647", schema = @Schema(minimum = "0")) @RequestParam(defaultValue = "0") int page,
                           @Parameter(description = "Page size; page multiplied by size must not exceed 2147483647", schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size,
                           @RequestParam(required = false) Boolean archived) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidPageException();
        }
        var pageable = PageRequest.of(page, size, Sort.by("id"));
        var result = archived == null ? repository.findAll(pageable) : repository.findByArchived(archived, pageable);
        return new TemplatePage(result.map(Template::from).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Operation(description = "Requires FULL_ACCESS and a CSRF token. Supply the current version.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Template.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token"),
            @ApiResponse(responseCode = "404", description = "Template not found", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Stale version", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PutMapping("/{id}")
    @Transactional
    Template edit(@PathVariable Long id, @Valid @RequestBody Edit request) {
        var entity = find(id, request.version().longValueExact());
        entity.setClassLabel(request.classLabel());
        entity.setScoringGroupLabel(request.scoringGroupLabel());
        entity.setCrewSize(request.crewSize().intValueExact());
        return Template.from(repository.saveAndFlush(entity));
    }

    @Operation(description = "Requires FULL_ACCESS and a CSRF token. Supply the current version.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Template.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token"),
            @ApiResponse(responseCode = "404", description = "Template not found", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Stale version", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PatchMapping("/{id}/archive")
    @Transactional
    Template archive(@PathVariable Long id, @Valid @RequestBody Version request) {
        return setArchived(id, request.version().longValueExact(), true);
    }

    @Operation(description = "Requires FULL_ACCESS and a CSRF token. Supply the current version.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Template.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token"),
            @ApiResponse(responseCode = "404", description = "Template not found", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Stale version", content = @Content(schema = @Schema(implementation = ApiError.class)))})
    @PatchMapping("/{id}/restore")
    @Transactional
    Template restore(@PathVariable Long id, @Valid @RequestBody Version request) {
        return setArchived(id, request.version().longValueExact(), false);
    }

    private static class InvalidPageException extends RuntimeException { }

    @ExceptionHandler(InvalidPageException.class)
    ResponseEntity<ApiError> invalidPage() {
        return ResponseEntity.badRequest().body(ApiError.of("request.validation_failed"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> malformedParameter() {
        return ResponseEntity.badRequest().body(ApiError.of("request.malformed"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> notFound(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(ApiError.of("category_template.not_found"));
    }

    private Template setArchived(Long id, long version, boolean archived) {
        var entity = find(id, version);
        entity.setArchived(archived);
        return Template.from(repository.saveAndFlush(entity));
    }

    private CategoryTemplate find(Long id, long version) {
        var entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (entity.getVersion() != version) {
            throw new OptimisticLockingFailureException("Stale category template");
        }
        return entity;
    }
}
