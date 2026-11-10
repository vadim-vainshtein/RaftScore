package kz.vainshtein.raftscore.catalog.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Reusable definition of a competition category. */
@Entity
@Table(name = "category_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryTemplate extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_label", nullable = false, columnDefinition = "TEXT")
    private String classLabel;

    @Column(name = "scoring_group_label", nullable = false, columnDefinition = "TEXT")
    private String scoringGroupLabel;

    @Column(name = "crew_size", nullable = false)
    private int crewSize;

    @Setter
    @Column(nullable = false)
    private boolean archived;

    @Builder
    private CategoryTemplate(String classLabel, String scoringGroupLabel, int crewSize, boolean archived) {
        setClassLabel(classLabel);
        setScoringGroupLabel(scoringGroupLabel);
        setCrewSize(crewSize);
        this.archived = archived;
    }

    public void setClassLabel(String classLabel) {
        this.classLabel = requireLabel(classLabel, "classLabel");
    }

    public void setScoringGroupLabel(String scoringGroupLabel) {
        this.scoringGroupLabel = requireLabel(scoringGroupLabel, "scoringGroupLabel");
    }

    public void setCrewSize(int crewSize) {
        if (crewSize <= 0) {
            throw new IllegalArgumentException("crewSize must be positive");
        }
        this.crewSize = crewSize;
    }

    public String getDisplayName() {
        return classLabel + " / " + scoringGroupLabel;
    }

    private static String requireLabel(String label, String name) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return label;
    }
}
