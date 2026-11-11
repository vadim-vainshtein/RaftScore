package kz.vainshtein.raftscore.timing.entities;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import kz.vainshtein.raftscore.timing.ElapsedTime;
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
        if (startInstant != null && finishInstant != null && finishInstant.isBefore(startInstant)) {
            throw new IllegalArgumentException("Finish must not be before start");
        }
        startInstant = startInstant == null ? null : startInstant.truncatedTo(ChronoUnit.MICROS);
        finishInstant = finishInstant == null ? null : finishInstant.truncatedTo(ChronoUnit.MICROS);
        var resolvedDuration = validateTiming(durationCentiseconds, startInstant, finishInstant, penaltyCentiseconds, status);
        this.durationCentiseconds = resolvedDuration;
        this.startInstant = startInstant;
        this.finishInstant = finishInstant;
        this.penaltyCentiseconds = penaltyCentiseconds;
        this.status = status;
    }

    private static Integer validateTiming(Integer duration, Instant start, Instant finish, int penalty,
                                       AttemptResultStatus status) {
        if (finish != null && start == null) {
            throw new IllegalArgumentException("finish requires start");
        }
        if (finish != null) {
            long elapsed;
            try {
                elapsed = ElapsedTime.centisecondsBetween(start, finish);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("elapsed time is too large", ex);
            }
            if (elapsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("elapsed time is too large");
            }
            if (duration != null && duration.longValue() != elapsed) {
                throw new IllegalArgumentException("duration must match start and finish");
            }
            if (duration == null) {
                duration = (int) elapsed;
            }
        } else if (start != null && duration != null) {
            throw new IllegalArgumentException("duration with start requires finish");
        }
        switch (status) {
            case FINISHED -> {
                if (duration == null && finish == null) {
                    throw new IllegalArgumentException("finished result requires duration or start and finish");
                }
            }
            case DNS -> {
                if (duration != null || start != null || finish != null || penalty != 0) {
                    throw new IllegalArgumentException("DNS cannot have timing or penalty");
                }
            }
            case DNF -> {
                if (duration != null || finish != null) {
                    throw new IllegalArgumentException("DNF cannot have duration or finish");
                }
            }
            case DSQ_ATTEMPT, DSQ_DISCIPLINE, DSQ_COMPETITION -> { }
        }
        return duration;
    }
}
