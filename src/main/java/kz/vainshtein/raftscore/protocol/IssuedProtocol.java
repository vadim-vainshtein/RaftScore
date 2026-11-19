package kz.vainshtein.raftscore.protocol;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An immutable document snapshot. Only its current/stale marker can change. */
@Entity
@Table(name = "issued_protocols")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class IssuedProtocol {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "competition_id", nullable = false, updatable = false)
    private long competitionId;
    @Column(nullable = false, updatable = false)
    private int edition;
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String snapshot;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String changes;
    @Column(nullable = false)
    private boolean stale;

    IssuedProtocol(long competitionId, int edition, Instant issuedAt, String snapshot, String changes) {
        this.competitionId = competitionId;
        this.edition = edition;
        this.issuedAt = issuedAt;
        this.snapshot = snapshot;
        this.changes = changes;
    }

    void markStale() { stale = true; }
}
