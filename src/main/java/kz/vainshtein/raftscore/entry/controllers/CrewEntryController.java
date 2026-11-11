package kz.vainshtein.raftscore.entry.controllers;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.core.JsonParser;
import kz.vainshtein.raftscore.competition.entities.EntryRequirementRepository;
import kz.vainshtein.raftscore.entry.entities.CrewRequirementValue;
import kz.vainshtein.raftscore.entry.entities.CrewRequirementValueRepository;
import kz.vainshtein.raftscore.entry.services.CrewEligibilityService;
import jakarta.persistence.EntityManager;
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
import kz.vainshtein.raftscore.entry.entities.CrewMembership;
import kz.vainshtein.raftscore.entry.entities.CrewMembershipRepository;
import kz.vainshtein.raftscore.entry.entities.Participant;
import kz.vainshtein.raftscore.entry.entities.ParticipantRepository;
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
    private final CrewMembershipRepository memberships;
    private final ParticipantRepository participants;
    private final EntityManager entityManager;
    private final EntryRequirementRepository requirements;
    private final CrewRequirementValueRepository requirementValues;
    private final CrewEligibilityService eligibility;

    record MemberRequest(
            @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal participantId,
            String surname, String givenName, String patronymic) { }
    record MemberResponse(Long participantId, String surname, String givenName, String patronymic) {
        static MemberResponse from(CrewMembership membership) {
            var person = membership.getParticipant();
            return new MemberResponse(person.getId(), person.getSurname(), person.getGivenName(), person.getPatronymic());
        }
    }

    record RequirementAnswer(@NotNull @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0) BigDecimal requirementId, @NotNull @JsonDeserialize(using = AnswerValueDeserializer.class) Object value) { }

    static class AnswerValueDeserializer extends ValueDeserializer<Object> {
        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) {
            return switch (parser.currentToken()) {
                case VALUE_TRUE -> true;
                case VALUE_FALSE -> false;
                case VALUE_STRING -> parser.getString();
                case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
                default -> { parser.skipChildren(); yield new Object(); }
            };
        }
    }

    record CreateRequest(
            @NotNull @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal categoryId,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber,
            @NotNull List<@NotNull @Valid MemberRequest> roster,
            List<@NotNull @Valid RequirementAnswer> requirementAnswers) { }

    record UpdateRequest(
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber,
            @NotNull @PositiveOrZero @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version,
            List<@NotNull @Valid MemberRequest> roster,
            List<@NotNull @Valid RequirementAnswer> requirementAnswers) { }

    record EntryResponse(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub,
                         String representative, int startNumber, long version, List<MemberResponse> roster, Boolean admissionDecision, boolean eligible) {
        static EntryResponse from(CrewEntry entry, List<CrewMembership> roster, boolean eligible) {
            var category = entry.getCompetitionCategory();
            return new EntryResponse(entry.getId(), category.getCompetition().getId(), category.getId(),
                    entry.getCrewName(), entry.getRegionOrClub(), entry.getRepresentative(), entry.getStartNumber(), entry.getVersion(), roster.stream().map(MemberResponse::from).toList(), entry.getAdmissionDecision(), eligible);
        }
    }

    record AdmissionRequest(@NotNull Boolean admitted,
            @NotNull @PositiveOrZero @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version) { }

    @GetMapping("/{entryId}")
    @Transactional(readOnly = true)
    EntryResponse get(@PathVariable Long competitionId, @PathVariable Long entryId) {
        var entry = entries.findByIdAndCompetitionCategoryCompetitionId(entryId, competitionId).orElseThrow(EntryNotFound::new);
        return EntryResponse.from(entry, memberships.findByCrewEntryIdOrderByPositionAsc(entryId), eligibility.isEligible(entry));
    }

    @Operation(summary = "Set crew admission decision", description = "Requires FULL_ACCESS, CSRF, confirmation mode and the current entry version.")
    @PutMapping("/{entryId}/admission")
    @Transactional
    EntryResponse admission(@PathVariable Long competitionId, @PathVariable Long entryId, @Valid @RequestBody AdmissionRequest request) {
        var entry = entries.findByIdAndCompetitionCategoryCompetitionId(entryId, competitionId).orElseThrow(EntryNotFound::new);
        var competition = entry.getCompetitionCategory().getCompetition();
        entityManager.refresh(competition, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!competition.isAdmissionConfirmationRequired()) throw new AdmissionNotRequired();
        var updated = entityManager.createNativeQuery("update crew_entries set admission_decision = :decision, version = version + 1 where id = :id and version = :version")
                .setParameter("decision", request.admitted()).setParameter("id", entryId)
                .setParameter("version", request.version().longValueExact()).executeUpdate();
        if (updated != 1) throw new OptimisticLockingFailureException("Stale entry version");
        entityManager.refresh(entry);
        return EntryResponse.from(entry, memberships.findByCrewEntryIdOrderByPositionAsc(entryId), eligibility.isEligible(entry));
    }

    @ExceptionHandler(AdmissionNotRequired.class)
    ResponseEntity<ApiError> admissionNotRequired() { return ResponseEntity.status(409).body(ApiError.of("crew_entry.admission_not_required")); }
    private static class AdmissionNotRequired extends RuntimeException { }
    @Operation(summary = "Create a crew entry", description = "Requires FULL_ACCESS and a CSRF token.")
    @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Competition or category not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Start number or participant already exists in category", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @Transactional
    ResponseEntity<EntryResponse> create(@PathVariable Long competitionId, @Valid @RequestBody CreateRequest request) {
        if (!competitions.existsById(competitionId)) throw new CompetitionNotFound();
        var category = categories.findByIdAndCompetitionId(request.categoryId().longValueExact(), competitionId).orElseThrow(CategoryNotFound::new);
        validateRoster(request.roster(), category.getCrewSize());
        var entry = entries.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName(request.crewName())
                .regionOrClub(request.regionOrClub()).representative(request.representative()).startNumber(request.startNumber().intValueExact()).build());
        var roster = replaceRoster(entry, request.roster());
        replaceAnswers(entry, request.requirementAnswers());
        return ResponseEntity.created(URI.create("/api/competitions/" + competitionId + "/entries/" + entry.getId())).body(EntryResponse.from(entry, roster, eligibility.isEligible(entry)));
    }

    @Operation(summary = "Update crew entry and roster", description = "Requires FULL_ACCESS, a CSRF token and the current version. Category cannot be changed.")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content(schema = @Schema(implementation = EntryResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Forbidden or missing CSRF token", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Entry not found in competition", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Stale version or start number or participant already exists in category", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{entryId}")
    @Transactional
    EntryResponse update(@PathVariable Long competitionId, @PathVariable Long entryId, @Valid @RequestBody UpdateRequest request) {
        var entry = entries.findByIdAndCompetitionCategoryCompetitionId(entryId, competitionId).orElseThrow(EntryNotFound::new);
        if (entry.getVersion() != request.version().longValueExact()) throw new OptimisticLockingFailureException("Stale entry version");
        var roster = memberships.findByCrewEntryIdOrderByPositionAsc(entryId);
        validateRoster(request.roster() == null ? roster : request.roster(), entry.getCompetitionCategory().getCrewSize());
        entry.updateDetails(request.crewName(), request.regionOrClub(), request.representative(), request.startNumber().intValueExact());
        entries.flush();
        if (request.roster() != null || request.requirementAnswers() != null) {
            if (entry.getVersion() == request.version().longValueExact()) {
                var updated = entityManager.createNativeQuery("update crew_entries set version = version + 1 where id = :id and version = :version")
                        .setParameter("id", entryId).setParameter("version", entry.getVersion()).executeUpdate();
                if (updated != 1) throw new OptimisticLockingFailureException("Stale entry version");
                entityManager.refresh(entry);
            }
            if (request.roster() != null) roster = replaceRoster(entry, request.roster());
            replaceAnswers(entry, request.requirementAnswers());
        }
        return EntryResponse.from(entry, roster, eligibility.isEligible(entry));
    }

    private void replaceAnswers(CrewEntry entry, List<RequirementAnswer> answers) {
        if (answers == null) return;
        var available = requirements.findByCompetitionId(entry.getCompetitionCategory().getCompetition().getId());
        var seen = new HashSet<Long>();
        var replacements = new ArrayList<CrewRequirementValue>();
        try {
            for (var answer : answers) {
                if (!seen.add(answer.requirementId().longValueExact())) throw new InvalidAnswers();
                var requirement = available.stream().filter(r -> r.getId().equals(answer.requirementId().longValueExact()))
                        .findFirst().orElseThrow(InvalidAnswers::new);
                var raw = answer.value();
                Object value = switch (requirement.getValueType()) {
                    case YES_NO -> raw instanceof Boolean ? raw : null;
                    case TEXT -> raw instanceof String ? raw : null;
                    case DATE -> raw instanceof String text ? LocalDate.parse(text) : null;
                    case NUMBER -> raw instanceof BigDecimal ? raw : null;
                };
                if (value == null) throw new InvalidAnswers();
                replacements.add(CrewRequirementValue.builder().crewEntry(entry).requirement(requirement).value(value).build());
            }
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            throw new InvalidAnswers();
        }
        requirementValues.deleteAll(requirementValues.findByCrewEntryId(entry.getId()));
        requirementValues.flush();
        requirementValues.saveAllAndFlush(replacements);
    }

    @ExceptionHandler(InvalidAnswers.class)
    ResponseEntity<ApiError> invalidAnswers() { return ResponseEntity.badRequest().body(ApiError.of("crew_entry.invalid_requirement_answers")); }
    private static class InvalidAnswers extends RuntimeException { }

    private void validateRoster(List<?> roster, int size) {
        if (roster == null || roster.size() != size) throw new InvalidRoster();
    }

    private List<CrewMembership> replaceRoster(CrewEntry entry, List<MemberRequest> roster) {
        var people = roster.stream().map(this::resolveParticipant).toList();
        memberships.deleteAll(memberships.findByCrewEntryIdOrderByPositionAsc(entry.getId()));
        memberships.flush();
        var result = new ArrayList<CrewMembership>();
        for (int i = 0; i < people.size(); i++) {
            var person = people.get(i);
            if (person.getId() == null) person = participants.save(person);
            result.add(memberships.save(CrewMembership.builder().crewEntry(entry).participant(person).position(i + 1).build()));
        }
        memberships.flush();
        return result;
    }

    private Participant resolveParticipant(MemberRequest member) {
        if (member == null) throw new InvalidRoster();
        if (member.participantId() != null) {
            if (member.surname() != null || member.givenName() != null || member.patronymic() != null) throw new InvalidRoster();
            return participants.findById(member.participantId().longValueExact()).orElseThrow(InvalidRoster::new);
        }
        if (member.surname() == null || member.surname().isBlank() || member.givenName() == null || member.givenName().isBlank()
                || member.surname().indexOf(0) >= 0 || member.givenName().indexOf(0) >= 0
                || (member.patronymic() != null && member.patronymic().indexOf(0) >= 0)) throw new InvalidRoster();
        return Participant.builder().surname(member.surname()).givenName(member.givenName()).patronymic(member.patronymic()).build();
    }

    @ExceptionHandler(InvalidRoster.class)
    ResponseEntity<ApiError> invalidRoster() { return ResponseEntity.badRequest().body(ApiError.of("crew_entry.invalid_roster")); }
    private static class InvalidRoster extends RuntimeException { }

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
            if (cause instanceof ConstraintViolationException violation
                    && ("crew_memberships_category_participant_uk".equals(violation.getConstraintName())
                    || "crew_memberships_crew_participant_uk".equals(violation.getConstraintName()))) {
                return ResponseEntity.status(409).body(ApiError.of("crew_entry.participant_duplicate"));
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
