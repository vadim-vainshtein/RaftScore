package kz.vainshtein.raftscore.slalom.entities;

import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Gate and attempt counts for one slalom discipline. */
@Entity
@Table(name = "slalom_configurations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlalomConfiguration extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discipline_id", nullable = false, updatable = false)
    private Discipline discipline;

    @Column(name = "gate_count", nullable = false, updatable = false)
    private int gateCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    public SlalomConfiguration(Discipline discipline, int gateCount) {
        this(discipline, gateCount, 2);
    }

    public SlalomConfiguration(Discipline discipline, int gateCount, int attemptCount) {
        this.discipline = Objects.requireNonNull(discipline, "discipline");
        if (discipline.getType() != DisciplineType.SLALOM) {
            throw new IllegalArgumentException("discipline must be slalom");
        }
        if (gateCount <= 0) throw new IllegalArgumentException("gateCount must be positive");
        setAttemptCount(attemptCount);
        this.gateCount = gateCount;
    }

    public void setAttemptCount(int attemptCount) {
        if (attemptCount <= 0) throw new IllegalArgumentException("attemptCount must be positive");
        this.attemptCount = attemptCount;
    }
}
