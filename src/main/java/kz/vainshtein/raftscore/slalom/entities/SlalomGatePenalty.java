package kz.vainshtein.raftscore.slalom.entities;

import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import kz.vainshtein.raftscore.timing.entities.AttemptResult;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Penalty in seconds for a single gate in a single attempt. */
@Entity
@Table(name = "slalom_gate_penalties")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlalomGatePenalty extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_result_id", nullable = false, updatable = false)
    private AttemptResult attempt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "configuration_id", nullable = false, updatable = false)
    private SlalomConfiguration configuration;
    @Column(name = "gate_number", nullable = false, updatable = false)
    private int gateNumber;
    @Column(name = "penalty_seconds", nullable = false)
    private int penaltySeconds;

    public SlalomGatePenalty(AttemptResult attempt, SlalomConfiguration configuration, int gateNumber, int penaltySeconds) {
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (gateNumber <= 0 || gateNumber > configuration.getGateCount()) throw new IllegalArgumentException("gateNumber outside configured range");
        this.gateNumber = gateNumber;
        var owner = configuration.getDiscipline();
        var actual = attempt.getDiscipline();
        if (owner != actual && (owner.getId() == null || !owner.getId().equals(actual.getId()))) {
            throw new IllegalArgumentException("gate and attempt must belong to the same discipline");
        }
        setPenaltySeconds(penaltySeconds);
    }

    public void setPenaltySeconds(int penaltySeconds) {
        if (penaltySeconds != 0 && penaltySeconds != 5 && penaltySeconds != 50) {
            throw new IllegalArgumentException("penaltySeconds must be 0, 5 or 50");
        }
        this.penaltySeconds = penaltySeconds;
    }
}
