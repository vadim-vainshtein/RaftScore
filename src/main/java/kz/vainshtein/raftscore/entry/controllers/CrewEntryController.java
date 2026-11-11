package kz.vainshtein.raftscore.entry.controllers;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
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

    record CreateRequest(
            @NotNull @DecimalMin("1") @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal categoryId,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber,
            @NotNull List<@NotNull @Valid MemberRequest> roster) { }

    record UpdateRequest(
            @NotBlank @Pattern(regexp = "[^\\x00]*") String crewName,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String regionOrClub,
            @NotBlank @Pattern(regexp = "[^\\x00]*") String representative,
            @NotNull @DecimalMin("1") @DecimalMax("2147483647") @Digits(integer = 10, fraction = 0)
            @Schema(implementation = Integer.class, type = "integer", format = "int32") BigDecimal startNumber,
            @NotNull @PositiveOrZero @DecimalMax("9223372036854775807") @Digits(integer = 19, fraction = 0)
            @Schema(implementation = Long.class, type = "integer", format = "int64") BigDecimal version,
            List<@NotNull @Valid MemberRequest> roster) { }

    record EntryResponse(Long id, Long competitionId, Long categoryId, String crewName, String regionOrClub,
                         String representative, int startNumber, long version, List<MemberResponse> roster) {
        static EntryResponse from(CrewEntry entry, List<CrewMembership> roster) {
            var category = entry.getCompetitionCategory();
            return new EntryResponse(entry.getId(), category.getCompetition().getId(), category.getId(),
                    entry.getCrewName(), entry.getRegionOrClub(), entry.getRepresentative(), entry.getStartNumber(), entry.getVersion(), roster.stream().map(MemberResponse::from).toList());
        }
    }

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
        return ResponseEntity.created(URI.create("/api/competitions/" + competitionId + "/entries/" + entry.getId())).body(EntryResponse.from(entry, replaceRoster(entry, request.roster())));
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
        if (request.roster() != null) {
            if (entry.getVersion() == request.version().longValueExact()) {
                var updated = entityManager.createNativeQuery("update crew_entries set version = version + 1 where id = :id and version = :version")
                        .setParameter("id", entryId).setParameter("version", entry.getVersion()).executeUpdate();
                if (updated != 1) throw new OptimisticLockingFailureException("Stale entry version");
                entityManager.refresh(entry);
            }
            roster = replaceRoster(entry, request.roster());
        }
        return EntryResponse.from(entry, roster);
    }

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
