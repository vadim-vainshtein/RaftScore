package kz.vainshtein.raftscore.entry.entities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.EntryRequirement;
import kz.vainshtein.raftscore.competition.entities.EntryRequirementValueType;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A crew's answer to one competition requirement. */
@Entity
@Table(name = "crew_requirement_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrewRequirementValue extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crew_entry_id", nullable = false, updatable = false)
    private CrewEntry crewEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false, updatable = false)
    private EntryRequirement requirement;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, updatable = false, columnDefinition = "TEXT")
    private EntryRequirementValueType valueType;

    @Column(name = "boolean_value") private Boolean booleanValue;
    @Column(name = "text_value", columnDefinition = "TEXT") private String textValue;
    @Column(name = "date_value") private LocalDate dateValue;
    @Column(name = "numeric_value", columnDefinition = "NUMERIC") private BigDecimal numericValue;

    @Builder
    private CrewRequirementValue(CrewEntry crewEntry, EntryRequirement requirement, Object value) {
        this.crewEntry = Objects.requireNonNull(crewEntry, "crewEntry");
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.valueType = requirement.getValueType();
        validateCompetition();
        setValue(value);
    }

    public void setValue(Object value) {
        Objects.requireNonNull(value, "value: omit the row for an absent answer");
        validateType();
        requirement.validateValue(value);
        if (value instanceof BigDecimal number) {
            // PostgreSQL NUMERIC supports at most 131072 digits before and 16383 after the decimal point.
            var integerDigits = (long) number.precision() - number.scale();
            if (integerDigits > 131072 || number.scale() > 16383) {
                throw new IllegalArgumentException("number exceeds PostgreSQL numeric range");
            }
        }
        booleanValue = value instanceof Boolean answer ? answer : null;
        textValue = value instanceof String answer ? answer : null;
        dateValue = value instanceof LocalDate answer ? answer : null;
        numericValue = value instanceof BigDecimal answer ? answer : null;
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        validateCompetition();
        Object value = booleanValue != null ? booleanValue : textValue != null ? textValue
                : dateValue != null ? dateValue : numericValue;
        Objects.requireNonNull(value, "value");
        validateType();
        requirement.validateValue(value);
    }

    private void validateType() {
        if (valueType != requirement.getValueType()) {
            throw new IllegalArgumentException("answer type must match requirement type");
        }
    }

    private void validateCompetition() {
        var crewCompetition = crewEntry.getCompetitionCategory().getCompetition();
        var requirementCompetition = requirement.getCompetition();
        if (crewCompetition != requirementCompetition && (crewCompetition.getId() == null
                || !crewCompetition.getId().equals(requirementCompetition.getId()))) {
            throw new IllegalArgumentException("crew and requirement must belong to the same competition");
        }
    }
}
