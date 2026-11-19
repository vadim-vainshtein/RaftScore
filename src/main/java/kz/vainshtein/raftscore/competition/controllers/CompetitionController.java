package kz.vainshtein.raftscore.competition.controllers;

import kz.vainshtein.raftscore.competition.entities.CompetitionStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import kz.vainshtein.raftscore.competition.entities.CompetitionListProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestController
@RequestMapping("/api/competitions")
@RequiredArgsConstructor
class CompetitionController {
    private final CompetitionRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final kz.vainshtein.raftscore.competition.CompletionCheck completionCheck;
    private final kz.vainshtein.raftscore.discipline.entities.DisciplineRepository disciplines;
    private final kz.vainshtein.raftscore.entry.entities.CrewEntryRepository entries;
    private final kz.vainshtein.raftscore.entry.services.CrewEligibilityService eligibility;

    @ExceptionHandler(kz.vainshtein.raftscore.entry.services.CrewEligibilityService.CompletedDiscipline.class)
    ResponseEntity<ApiError> completedDiscipline() { return ResponseEntity.status(409).body(ApiError.of("crew_entry.completed_discipline")); }
    @ExceptionHandler(kz.vainshtein.raftscore.entry.services.CrewEligibilityService.RecordedResults.class)
    ResponseEntity<ApiError> recordedResults() { return ResponseEntity.status(409).body(ApiError.of("crew_entry.recorded_results")); }

    @Operation(summary = "List competitions", description = "Authenticated users can search titles and sort by start date.")
    @ApiResponse(responseCode = "200", description = "Page of competitions", content = @Content(schema = @Schema(implementation = CompetitionPage.class)))
    @ApiResponse(responseCode = "400", description = "Invalid parameters", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @GetMapping
    @Transactional(readOnly = true)
    CompetitionPage list(
            @Parameter(description = "Zero-based page; page multiplied by size must not exceed 2147483647", schema = @Schema(minimum = "0")) @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", schema = @Schema(minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Case-insensitive literal title search") @RequestParam(defaultValue = "") String search,
            @Parameter(description = "Start date order") @RequestParam(defaultValue = "ASC") SortDirection sort) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE || search.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid list parameters");
        }
        var direction = Sort.Direction.valueOf(sort.name());
        var result = repository.findList(search, PageRequest.of(page, size, Sort.by(direction, "startDate").and(Sort.by("id"))));

        return new CompetitionPage(result.map(item -> CompetitionListItem.from(item)).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    enum SortDirection { ASC, DESC }
    record CompetitionPage(List<CompetitionListItem> content, int page, int size, long totalElements, int totalPages) { }
    record CompetitionListItem(Long id, String title, LocalDate startDate, LocalDate endDate, String location,
                               String timeZone, CompetitionStatus state, long categoryCount, long crewCount) {
        static CompetitionListItem from(CompetitionListProjection item) {
            return new CompetitionListItem(item.getId(), item.getTitle(), item.getStartDate(), item.getEndDate(),
                    item.getLocation(), item.getTimeZone(), item.getStatus(), item.getCategoryCount(), item.getCrewCount());
        }
    }
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    CompetitionResponse get(@PathVariable Long id) {
        return CompetitionResponse.from(repository.findById(id).orElseThrow(NotFound::new));
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    @Transactional
    ResponseEntity<CompetitionResponse> create(@Valid @RequestBody CreateRequest request) {
        var competition = repository.saveAndFlush(request.toCompetition());
        return ResponseEntity.created(URI.create("/api/competitions/" + competition.getId()))
                .body(CompetitionResponse.from(competition));
    }

    @ApiResponse(responseCode = "200", description = "Competition updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CompetitionResponse.class)))
    @ApiResponse(responseCode = "409", description = "Competition was modified concurrently",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}")
    @Transactional
    CompetitionResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        var details = request.details();
        details.validateDates();
        var competition = repository.findForDisciplineConfiguration(id).orElseThrow(NotFound::new);
        if (competition.getStatus() == CompetitionStatus.COMPLETED) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT);
        if (competition.getVersion() != request.version().longValueExact()) {
            throw new OptimisticLockingFailureException("Stale competition version");
        }
        var allCrews = entries.findByCompetitionCategoryCompetitionId(id);
        var previousEligibility = new java.util.HashMap<Long, Boolean>();
        for (var crew : allCrews) previousEligibility.put(crew.getId(), eligibility.isEligible(crew));
        competition.updateDetails(details.title(), details.startDate(), details.endDate(), details.location(),
                details.timeZone(), details.description(), details.admissionConfirmationRequired());
        for (var crew : allCrews) {
            eligibility.requireNoNewEligibleCrew(crew, previousEligibility.get(crew.getId()));
            if (!eligibility.isEligible(crew)) { eligibility.requireNoRecordedResults(crew.getId()); eligibility.withdrawFromUnplayedBrackets(crew.getId()); }
        }
        competition.setRepresentativeFields(request.representativeFields());
        repository.flush();
        return CompetitionResponse.from(competition);
    }

    record StatusRequest(@NotNull CompetitionStatus status,
                         @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @DecimalMax("9223372036854775807") BigDecimal version) { }

    @GetMapping("/{id}/completion-issues")
    @Transactional(readOnly = true)
    List<kz.vainshtein.raftscore.competition.CompletionCheck.Issue> completionIssues(@PathVariable Long id) {
        repository.findById(id).orElseThrow(NotFound::new);
        return completionCheck.competition(id);
    }

    @GetMapping("/{id}/completion-overview")
    @Transactional(readOnly = true)
    kz.vainshtein.raftscore.competition.CompletionCheck.Overview completionOverview(@PathVariable Long id) {
        repository.findById(id).orElseThrow(NotFound::new);
        return completionCheck.overview(id);
    }

    @PutMapping("/{id}/status")
    @Transactional
    CompetitionResponse transition(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        var competition = repository.findForDisciplineConfiguration(id).orElseThrow(NotFound::new);
        if (competition.getVersion() != request.version().longValueExact()) {
            throw new OptimisticLockingFailureException("Stale competition version");
        }
        if (request.status() == CompetitionStatus.COMPLETED && !completionCheck.competition(id).isEmpty())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Competition has incomplete results");
        try { competition.transitionTo(request.status()); }
        catch (IllegalStateException ex) { throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex); }
        if (request.status() == CompetitionStatus.COMPLETED) completeConductedDisciplines(id);
        repository.flush();
        return CompetitionResponse.from(competition);
    }

    private void completeConductedDisciplines(long competitionId) {
        for (var disciplineId : disciplines.findIdsByCompetitionId(competitionId)) {
            var discipline = disciplines.findForStartListUpdate(disciplineId).orElseThrow();
            if (discipline.getStatus() != kz.vainshtein.raftscore.discipline.entities.DisciplineStatus.STARTED) continue;
            var hasResults = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM attempt_results WHERE discipline_id = ?)
                        OR EXISTS (SELECT 1 FROM parallel_sprint_brackets b JOIN parallel_sprint_heats h ON h.bracket_id = b.id
                                   WHERE b.discipline_id = ? AND (h.status_1 IS NOT NULL OR h.status_2 IS NOT NULL OR h.winner_overridden))
                    """, Boolean.class, disciplineId, disciplineId);
            if (Boolean.TRUE.equals(hasResults)) discipline.transitionTo(kz.vainshtein.raftscore.discipline.entities.DisciplineStatus.COMPLETED);
        }
        disciplines.flush();
    }

    @GetMapping("/{id}/tiebreak")
    @Transactional(readOnly = true)
    TiebreakResponse tiebreak(@PathVariable Long id) {
        return TiebreakResponse.from(repository.findById(id).orElseThrow(NotFound::new));
    }

    @PutMapping("/{id}/tiebreak")
    @Transactional
    TiebreakResponse updateTiebreak(@PathVariable Long id, @Valid @RequestBody TiebreakRequest request) {
        var competition = repository.findForDisciplineConfiguration(id).orElseThrow(NotFound::new);
        if (competition.getStatus() == CompetitionStatus.COMPLETED) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT);
        if (competition.getVersion() != request.version().longValueExact()) {
            throw new OptimisticLockingFailureException("Stale competition version");
        }
        var discipline = request.disciplineId() == null ? null : disciplines.findById(request.disciplineId().longValueExact())
                .orElseThrow(() -> new IllegalArgumentException("Unknown discipline"));
        competition.setTiebreakDiscipline(discipline);
        repository.flush();
        return TiebreakResponse.from(competition);
    }

    record TiebreakRequest(@jakarta.validation.constraints.Positive @Digits(integer = 19, fraction = 0)
            @DecimalMax("9223372036854775807")
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal disciplineId,
            @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @DecimalMax("9223372036854775807")
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version) { }
    record TiebreakResponse(Long disciplineId, long version) {
        static TiebreakResponse from(Competition competition) {
            return new TiebreakResponse(competition.getTiebreakDiscipline() == null ? null
                    : competition.getTiebreakDiscipline().getId(), competition.getVersion());
        }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(404).body(ApiError.of("competition.not_found"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidPathParameter() {
        return ResponseEntity.badRequest().body(ApiError.of("request.malformed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidDetails() {
        return ResponseEntity.badRequest().body(new ApiError("request.validation_failed", Map.of(), List.of()));
    }

    private static class NotFound extends RuntimeException { }

    record CreateRequest(@NotBlank @Pattern(regexp = "[^\\x00]*") String title,
                         @NotNull LocalDate startDate, LocalDate endDate,
                         @NotBlank @Pattern(regexp = "[^\\x00]*") String location,
                         @NotBlank @Pattern(regexp = "[^\\x00]*") String timeZone,
                         @Pattern(regexp = "[^\\x00]*") String description,
                         @NotNull Boolean admissionConfirmationRequired, Map<String, String> representativeFields) {
        // PostgreSQL and JSON clients share the conventional four-digit ISO calendar range.
        void validateDates() {
            if (!supportedDate(startDate) || (endDate != null && !supportedDate(endDate))) {
                throw new IllegalArgumentException("Date outside supported range");
            }
        }

        private boolean supportedDate(LocalDate date) {
            return date != null && date.getYear() >= 1 && date.getYear() <= 9999;
        }

        Competition toCompetition() {
            validateDates();
            var competition = Competition.builder().title(title).startDate(startDate).endDate(endDate).location(location)
                    .timeZone(timeZone).description(description)
                    .admissionConfirmationRequired(admissionConfirmationRequired).build();
            competition.setRepresentativeFields(representativeFields);
            return competition;
        }
    }

    record UpdateRequest(@NotBlank @Pattern(regexp = "[^\\x00]*") String title,
                         @NotNull LocalDate startDate, LocalDate endDate,
                         @NotBlank @Pattern(regexp = "[^\\x00]*") String location,
                         @NotBlank @Pattern(regexp = "[^\\x00]*") String timeZone,
                         @Pattern(regexp = "[^\\x00]*") String description,
                         @NotNull Boolean admissionConfirmationRequired, Map<String, String> representativeFields,
                         @NotNull @PositiveOrZero @Digits(integer = 19, fraction = 0) @DecimalMax("9223372036854775807")
                         @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version) {
        CreateRequest details() {
            return new CreateRequest(title, startDate, endDate, location, timeZone, description,
                    admissionConfirmationRequired, representativeFields);
        }
    }

    record CompetitionResponse(Long id, long version, String title, LocalDate startDate, LocalDate endDate,
                               String location, String timeZone, String description,
                               boolean admissionConfirmationRequired, CompetitionStatus status, Map<String, String> representativeFields) {
        static CompetitionResponse from(Competition competition) {
            return new CompetitionResponse(competition.getId(), competition.getVersion(), competition.getTitle(),
                    competition.getStartDate(), competition.getEndDate(), competition.getLocation(),
                    competition.getTimeZone(), competition.getDescription(), competition.isAdmissionConfirmationRequired(), competition.getStatus(), competition.getRepresentativeFields());
        }
    }
}
