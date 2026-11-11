package kz.vainshtein.raftscore.timing.entities;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The stored result of one crew's attempt in a discipline. */
@Entity
@Table(name = "attempt_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttemptResult extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discipline_id", nullable = false, updatable = false)
    private Discipline discipline;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crew_entry_id", nullable = false, updatable = false)
    private CrewEntry crewEntry;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "duration_centiseconds")
    private Integer durationCentiseconds;

    @Column(name = "start_instant")
    private Instant startInstant;

    @Column(name = "finish_instant")
    private Instant finishInstant;

    @Column(name = "penalty_centiseconds", nullable = false)
    private int penaltyCentiseconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private AttemptResultStatus status;

    @Builder
    private AttemptResult(Discipline discipline, CrewEntry crewEntry, int attemptNumber,
                          Integer durationCentiseconds, Instant startInstant, Instant finishInstant,
                          int penaltyCentiseconds, AttemptResultStatus status) {
        this.discipline = Objects.requireNonNull(discipline, "discipline");
        this.crewEntry = Objects.requireNonNull(crewEntry, "crewEntry");
        var category = crewEntry.getCompetitionCategory();
        if (discipline.getCategories().stream().noneMatch(selected -> selected == category
                || (selected.getId() != null && selected.getId().equals(category.getId())))) {
            throw new IllegalArgumentException("crew category must be selected for the discipline");
        }
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        this.attemptNumber = attemptNumber;
        updateResult(durationCentiseconds, startInstant, finishInstant, penaltyCentiseconds, status);
    }

    public void updateResult(Integer durationCentiseconds, Instant startInstant, Instant finishInstant,
                             int penaltyCentiseconds, AttemptResultStatus status) {
        if (durationCentiseconds != null && durationCentiseconds < 0) {
            throw new IllegalArgumentException("durationCentiseconds must not be negative");
        }
        if (penaltyCentiseconds < 0) {
            throw new IllegalArgumentException("penaltyCentiseconds must not be negative");
        }
        Objects.requireNonNull(status, "status");
        this.durationCentiseconds = durationCentiseconds;
        this.startInstant = startInstant;
        this.finishInstant = finishInstant;
        this.penaltyCentiseconds = penaltyCentiseconds;
        this.status = status;
    }
}
