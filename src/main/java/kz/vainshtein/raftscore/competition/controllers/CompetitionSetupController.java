package kz.vainshtein.raftscore.competition.controllers;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Creates an entire competition draft in one transaction. References are zero-based draft indexes. */
@RestController
@RequestMapping("/api/competitions/setup")
@RequiredArgsConstructor
class CompetitionSetupController {
    private final CompetitionRepository competitions;
    private final CategoryTemplateRepository templates;
    private final CompetitionCategoryRepository categories;
    private final EntryRequirementRepository requirements;
    private final DisciplineRepository disciplines;
    private final PlacementPointsTableRepository tables;
    private final SlalomConfigurationRepository slalom;

    record Setup(@NotNull @Valid CompetitionController.CreateRequest competition,
                 @Valid List<@NotNull @Valid Category> categories,
                 @Valid List<@NotNull @Valid Requirement> requirements,
                 @Valid List<@NotNull @Valid DisciplineDraft> disciplines,
                 @JsonDeserialize(using = StrictInteger.class) Integer tiebreakDisciplineIndex) { }
    record Category(@JsonDeserialize(using = StrictLong.class) Long templateId,
                    @Pattern(regexp = "[^\\x00]*") String classLabel,
                    @Pattern(regexp = "[^\\x00]*") String scoringGroupLabel,
                    @Positive @JsonDeserialize(using = StrictInteger.class) Integer crewSize) { }
    record Requirement(@NotBlank @Pattern(regexp = "[^\\x00]*") String name,
                       @NotNull EntryRequirementValueType valueType, @NotNull Boolean required,
                       @NotNull @PositiveOrZero @JsonDeserialize(using = StrictInteger.class) Integer displayOrder) { }
    record DisciplineDraft(@NotBlank @Pattern(regexp = "[^\\x00]*") String name, @NotNull DisciplineType type,
                           @NotNull @PositiveOrZero @JsonDeserialize(using = StrictInteger.class) Integer displayOrder,
                           @NotNull @JsonDeserialize(contentUsing = StrictInteger.class) List<@NotNull Integer> categoryIndexes,
                           @Positive @JsonDeserialize(using = StrictInteger.class) Integer gateCount,
                           @Positive @JsonDeserialize(using = StrictInteger.class) Integer attemptCount,
                           @JsonDeserialize(using = StrictInteger.class) Integer qualificationDisciplineIndex,
                           @Valid PointsTable pointsTable) { }
    record PointsTable(@Pattern(regexp = "[^\\x00]*") String name, @NotNull TiePointsPolicy tiePolicy,
                       @NotNull @Valid List<@NotNull @Valid PointsRow> rows) { }
    record PointsRow(@NotNull @Positive @JsonDeserialize(using = StrictInteger.class) Integer place,
                     @NotNull @PositiveOrZero @JsonDeserialize(using = StrictInteger.class) Integer points) { }

    @PostMapping
    @Transactional
    ResponseEntity<CompetitionController.CompetitionResponse> create(@Valid @RequestBody Setup request) {
        var competition = competitions.save(request.competition().toCompetition());
        var savedCategories = saveCategories(competition, orEmpty(request.categories()));
        saveRequirements(competition, orEmpty(request.requirements()));
        var savedDisciplines = saveDisciplines(competition, savedCategories, orEmpty(request.disciplines()));
        if (request.tiebreakDisciplineIndex() != null) {
            competition.setTiebreakDiscipline(at(savedDisciplines, request.tiebreakDisciplineIndex()));
        }
        competitions.flush();
        return ResponseEntity.created(URI.create("/api/competitions/" + competition.getId()))
                .body(CompetitionController.CompetitionResponse.from(competition));
    }

    private List<CompetitionCategory> saveCategories(Competition competition, List<Category> drafts) {
        var result = new ArrayList<CompetitionCategory>();
        for (var draft : drafts) {
            var template = draft.templateId() == null ? null : templates.findForSnapshot(draft.templateId())
                    .filter(t -> !t.isArchived()).orElseThrow(() -> new IllegalArgumentException("active template required"));
            var classLabel = draft.classLabel() != null ? draft.classLabel() : template == null ? null : template.getClassLabel();
            var group = draft.scoringGroupLabel() != null ? draft.scoringGroupLabel() : template == null ? null : template.getScoringGroupLabel();
            var size = draft.crewSize() != null ? draft.crewSize() : template == null ? null : template.getCrewSize();
            if (size == null) throw new IllegalArgumentException("crew size required");
            result.add(categories.save(CompetitionCategory.builder().competition(competition).classLabel(classLabel)
                    .scoringGroupLabel(group).crewSize(size).build()));
        }
        return result;
    }

    private void saveRequirements(Competition competition, List<Requirement> drafts) {
        for (var draft : drafts) requirements.save(EntryRequirement.builder().competition(competition).name(draft.name())
                .valueType(draft.valueType()).required(draft.required()).displayOrder(draft.displayOrder()).build());
    }

    private List<Discipline> saveDisciplines(Competition competition, List<CompetitionCategory> categories, List<DisciplineDraft> drafts) {
        var result = new ArrayList<Discipline>();
        for (var draft : drafts) {
            if (new HashSet<>(draft.categoryIndexes()).size() != draft.categoryIndexes().size()) throw new IllegalArgumentException("duplicate category");
            if (draft.type() == DisciplineType.SLALOM ? draft.gateCount() == null || draft.attemptCount() == null
                    : draft.gateCount() != null || draft.attemptCount() != null) throw new IllegalArgumentException("invalid slalom configuration");
            var discipline = Discipline.builder().competition(competition).name(draft.name()).type(draft.type()).displayOrder(draft.displayOrder()).build();
            discipline.replaceCategories(draft.categoryIndexes().stream().map(index -> at(categories, index)).toList());
            if (draft.pointsTable() != null) {
                var table = draft.pointsTable();
                discipline.setPointsTable(tables.save(new PlacementPointsTable(competition, table.name(), table.tiePolicy(),
                        table.rows().stream().map(row -> new PlacementPointsRow(row.place(), row.points())).toList())));
            }
            result.add(disciplines.save(discipline));
            if (draft.type() == DisciplineType.SLALOM) slalom.save(new SlalomConfiguration(discipline, draft.gateCount(), draft.attemptCount()));
        }
        for (int i = 0; i < drafts.size(); i++) {
            var index = drafts.get(i).qualificationDisciplineIndex();
            if (index == null) continue;
            var discipline = result.get(i);
            var qualification = at(result, index);
            if (discipline.getType() != DisciplineType.PARALLEL_SPRINT || qualification == discipline
                    || qualification.getType() == DisciplineType.PARALLEL_SPRINT
                    || !qualification.getCategories().containsAll(discipline.getCategories())) throw new IllegalArgumentException("invalid qualification");
            discipline.setQualificationDiscipline(qualification);
        }
        return result;
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.dao.DataIntegrityViolationException.class})
    ResponseEntity<kz.vainshtein.raftscore.api.ApiError> invalid() {
        return ResponseEntity.badRequest().body(kz.vainshtein.raftscore.api.ApiError.of("request.validation_failed"));
    }
    private static <T> T at(List<T> list, int index) {
        if (index < 0 || index >= list.size()) throw new IllegalArgumentException("invalid draft index");
        return list.get(index);
    }
    private static <T> List<T> orEmpty(List<T> list) { return list == null ? List.of() : list; }
    static class StrictInteger extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getIntValue();
        }
    }
    static class StrictLong extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("integer required");
            return parser.getLongValue();
        }
    }
}
