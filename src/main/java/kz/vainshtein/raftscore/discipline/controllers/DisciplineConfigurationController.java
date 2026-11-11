package kz.vainshtein.raftscore.discipline.controllers;

import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.scoring.entities.PlacementPointsTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Configuration contract, separate from the existing points-table update contract. */
@RestController
@RequestMapping("/api/competitions/{competitionId}/disciplines")
@RequiredArgsConstructor
class DisciplineConfigurationController {
    private final DisciplineRepository disciplines;
    private final CompetitionRepository competitions;
    private final CompetitionCategoryRepository categories;
    private final SlalomConfigurationRepository slalom;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PlacementPointsTableRepository tables;

    record Configuration(@NotBlank String name, @NotNull DisciplineType type,
                         @NotNull @Min(0) @JsonDeserialize(using = StrictInteger.class) Integer displayOrder,
                         @NotNull @JsonDeserialize(contentUsing = StrictLong.class) List<@NotNull Long> categoryIds,
                         @Positive @JsonDeserialize(using = StrictInteger.class) Integer gateCount,
                         @Positive @JsonDeserialize(using = StrictInteger.class) Integer attemptCount,
                         @JsonDeserialize(using = StrictLong.class) Long qualificationDisciplineId,
                         @PositiveOrZero @JsonDeserialize(using = StrictLong.class) Long version) { }
    record View(long id, String name, DisciplineType type, int displayOrder, List<Long> categoryIds,
                Integer gateCount, Integer attemptCount, Long qualificationDisciplineId, Long pointsTableId, long version, boolean resultsEntered) { }

    @GetMapping
    @Transactional(readOnly = true)
    List<View> list(@PathVariable Long competitionId) {
        if (!competitions.existsById(competitionId)) throw notFound();
        return disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(competitionId).stream().map(this::view).toList();
    }

    record PointsTable(long id, String name) { }
    @GetMapping("/points-tables")
    @Transactional(readOnly = true)
    List<PointsTable> pointsTables(@PathVariable Long competitionId) {
        if (!competitions.existsById(competitionId)) throw notFound();
        return tables.findByCompetitionIdOrderByIdAsc(competitionId).stream().map(t -> new PointsTable(t.getId(), t.getName())).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    View create(@PathVariable Long competitionId, @Valid @RequestBody Configuration request) {
        var competition = competitions.findForDisciplineConfiguration(competitionId).orElseThrow(DisciplineConfigurationController::notFound);
        var discipline = Discipline.builder().competition(competition).name(request.name()).type(request.type())
                .displayOrder(request.displayOrder()).build();
        apply(discipline, competitionId, request, false);
        disciplines.saveAndFlush(discipline);
        saveSlalom(discipline, request);
        return view(discipline);
    }

    @PutMapping("/{disciplineId}/configuration")
    @Transactional
    View update(@PathVariable Long competitionId, @PathVariable Long disciplineId, @Valid @RequestBody Configuration request) {
        competitions.findForDisciplineConfiguration(competitionId).orElseThrow(DisciplineConfigurationController::notFound);
        var discipline = disciplines.findForStartListUpdate(disciplineId)
                .filter(d -> d.getCompetition().getId().equals(competitionId)).orElseThrow(DisciplineConfigurationController::notFound);
        if (request.version() == null) throw badRequest("version required");
        if (discipline.getVersion() != request.version()) throw new OptimisticLockingFailureException("Stale discipline version");
        if (discipline.getType() != request.type()) throw new ResponseStatusException(HttpStatus.CONFLICT, "discipline type cannot be changed");
        apply(discipline, competitionId, request, true);
        disciplines.saveAndFlush(discipline);
        if (discipline.getVersion() == request.version()) {
            entityManager.lock(discipline, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            disciplines.flush();
        }
        saveSlalom(discipline, request);
        return view(discipline);
    }

    private void apply(Discipline discipline, Long competitionId, Configuration request, boolean existing) {
        var selected = request.categoryIds().stream().distinct().map(id -> categories.findByIdAndCompetitionId(id, competitionId)
                .orElseThrow(DisciplineConfigurationController::notFound)).toList();
        if (selected.size() != request.categoryIds().size()) throw badRequest("duplicate category");
        if (request.type() == DisciplineType.SLALOM && (request.gateCount() == null || request.attemptCount() == null)) throw badRequest("slalom counts required");
        if (request.type() != DisciplineType.SLALOM && (request.gateCount() != null || request.attemptCount() != null)) throw badRequest("slalom counts only apply to slalom");
        Discipline qualification = null;
        if (request.qualificationDisciplineId() != null) {
            if (request.type() != DisciplineType.PARALLEL_SPRINT) throw badRequest("qualification only applies to parallel sprint");
            qualification = disciplines.findById(request.qualificationDisciplineId()).filter(d -> d.getCompetition().getId().equals(competitionId))
                    .orElseThrow(DisciplineConfigurationController::notFound);
            if (qualification == discipline || qualification.getType() == DisciplineType.PARALLEL_SPRINT || !qualification.getCategories().containsAll(selected)) throw badRequest("invalid qualification");
        }
        if (existing && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from disciplines where qualification_discipline_id = ?)", Boolean.class, discipline.getId())) &&
                (discipline.getType() != request.type() || !discipline.getCategories().stream().map(CompetitionCategory::getId).sorted().toList().equals(selected.stream().map(CompetitionCategory::getId).sorted().toList())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "discipline is used for qualification");
        if (existing && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from parallel_sprint_brackets where discipline_id = ?)", Boolean.class, discipline.getId())) &&
                !java.util.Objects.equals(discipline.getQualificationDiscipline() == null ? null : discipline.getQualificationDiscipline().getId(), request.qualificationDisciplineId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bracket already seeded");
        if (existing && hasActivity(discipline.getId())) {
            var oldIds = discipline.getCategories().stream().map(CompetitionCategory::getId).sorted().toList();
            var newIds = selected.stream().map(CompetitionCategory::getId).sorted().toList();
            var config = slalom.findByDisciplineId(discipline.getId()).orElse(null);
            if (discipline.getType() != request.type() || !oldIds.equals(newIds) || (config != null && (!Integer.valueOf(config.getGateCount()).equals(request.gateCount()) || !Integer.valueOf(config.getAttemptCount()).equals(request.attemptCount()))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "configuration has recorded activity");
        }
        discipline.setName(request.name()); discipline.setType(request.type()); discipline.setDisplayOrder(request.displayOrder());
        discipline.replaceCategories(selected); discipline.setQualificationDiscipline(qualification);
    }

    private boolean hasActivity(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from start_lists where discipline_id = ? union all select 1 from attempt_results where discipline_id = ? union all select 1 from parallel_sprint_brackets where discipline_id = ?)", Boolean.class, id, id, id));
    }
    private void saveSlalom(Discipline discipline, Configuration request) {
        var previous = slalom.findByDisciplineId(discipline.getId());
        if (request.type() == DisciplineType.SLALOM) {
            var config = previous.orElseGet(() -> new SlalomConfiguration(discipline, request.gateCount(), request.attemptCount()));
            config.setGateCount(request.gateCount()); config.setAttemptCount(request.attemptCount()); slalom.saveAndFlush(config);
        } else previous.ifPresent(slalom::delete);
    }
    private View view(Discipline d) {
        var config = slalom.findByDisciplineId(d.getId()).orElse(null);
        return new View(d.getId(), d.getName(), d.getType(), d.getDisplayOrder(), d.getCategories().stream().map(CompetitionCategory::getId).sorted().toList(),
                config == null ? null : config.getGateCount(), config == null ? null : config.getAttemptCount(),
                d.getQualificationDiscipline() == null ? null : d.getQualificationDiscipline().getId(), d.getPointsTable() == null ? null : d.getPointsTable().getId(), d.getVersion(), Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from attempt_results where discipline_id = ? union all select 1 from parallel_sprint_brackets b join parallel_sprint_heats h on h.bracket_id = b.id where b.discipline_id = ? and (h.status_1 is not null or h.status_2 is not null))", Boolean.class, d.getId(), d.getId())));
    }
    static class StrictLong extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getLongValue();
        }
    }
    static class StrictInteger extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getIntValue();
        }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
