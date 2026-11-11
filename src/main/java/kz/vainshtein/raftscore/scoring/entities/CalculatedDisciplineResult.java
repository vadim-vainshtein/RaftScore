package kz.vainshtein.raftscore.scoring.entities;

import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The latest stored calculated result for a crew in a discipline. */
@Entity
@Table(name = "calculated_discipline_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalculatedDisciplineResult extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discipline_id", nullable = false, updatable = false)
    private Discipline discipline;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crew_entry_id", nullable = false, updatable = false)
    private CrewEntry crewEntry;

    @Column(name = "place")
    private Integer place;

    @Column(name = "total_centiseconds")
    private Long totalCentiseconds;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private CalculatedResultStatus status;

    @Column(nullable = false)
    private int points;

    @Builder
    private CalculatedDisciplineResult(Discipline discipline, CrewEntry crewEntry, Integer place,
                                       Long totalCentiseconds, String summary, CalculatedResultStatus status, int points) {
        this.discipline = Objects.requireNonNull(discipline, "discipline");
        this.crewEntry = Objects.requireNonNull(crewEntry, "crewEntry");
        var category = crewEntry.getCompetitionCategory();
        if (discipline.getCategories().stream().noneMatch(selected -> selected == category
                || (selected.getId() != null && selected.getId().equals(category.getId())))) {
            throw new IllegalArgumentException("crew category must be selected for the discipline");
        }
        updateResult(place, totalCentiseconds, summary, status, points);
    }

    public void updateResult(Integer place, Long totalCentiseconds, String summary,
                             CalculatedResultStatus status, int points) {
        if (place != null && place <= 0) throw new IllegalArgumentException("place must be positive");
        if (totalCentiseconds != null && totalCentiseconds < 0) throw new IllegalArgumentException("totalCentiseconds must not be negative");
        if (summary != null && (summary.length() > 1000 || summary.indexOf('\0') >= 0)) throw new IllegalArgumentException("invalid summary");
        Objects.requireNonNull(status, "status");
        if (points < 0) throw new IllegalArgumentException("points must not be negative");
        this.place = place;
        this.totalCentiseconds = totalCentiseconds;
        this.summary = summary;
        this.status = status;
        this.points = points;
    }
}
