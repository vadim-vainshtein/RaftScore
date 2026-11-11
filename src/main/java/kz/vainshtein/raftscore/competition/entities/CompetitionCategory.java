package kz.vainshtein.raftscore.competition.entities;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A competition's independently editable category. */
@Entity
@Table(name = "competition_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetitionCategory extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    @Column(name = "class_label", nullable = false, columnDefinition = "TEXT")
    private String classLabel;

    @Column(name = "scoring_group_label", nullable = false, columnDefinition = "TEXT")
    private String scoringGroupLabel;

    @Column(name = "crew_size", nullable = false)
    private int crewSize;

    @Builder
    private CompetitionCategory(Competition competition, String classLabel, String scoringGroupLabel, int crewSize) {
        this.competition = Objects.requireNonNull(competition, "competition");
        setClassLabel(classLabel);
        setScoringGroupLabel(scoringGroupLabel);
        setCrewSize(crewSize);
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
