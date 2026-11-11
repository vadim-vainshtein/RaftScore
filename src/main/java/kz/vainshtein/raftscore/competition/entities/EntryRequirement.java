package kz.vainshtein.raftscore.competition.entities;

import java.util.Objects;
import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A configurable requirement owned by a competition. */
@Entity
@Table(name = "entry_requirements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntryRequirement extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, columnDefinition = "TEXT")
    private EntryRequirementValueType valueType;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Builder
    private EntryRequirement(Competition competition, String name, EntryRequirementValueType valueType,
                             boolean required, int displayOrder) {
        this.competition = Objects.requireNonNull(competition, "competition");
        setName(name);
        setValueType(valueType);
        this.required = required;
        setDisplayOrder(displayOrder);
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    public void setValueType(EntryRequirementValueType valueType) {
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    /** Validates a typed answer, with null representing an absent answer. */
    public void validateValue(Object value) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("value is required");
            }
            return;
        }
        var valid = switch (valueType) {
            case YES_NO -> value instanceof Boolean;
            case TEXT -> value instanceof String text && !text.isBlank() && text.indexOf('\0') < 0;
            case DATE -> value instanceof LocalDate date && date.getYear() >= 1 && date.getYear() <= 9999;
            case NUMBER -> value instanceof BigDecimal;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid value for " + valueType);
        }
    }

    public void setDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must not be negative");
        }
        this.displayOrder = displayOrder;
    }
}
