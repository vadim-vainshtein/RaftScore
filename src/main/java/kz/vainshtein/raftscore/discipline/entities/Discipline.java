package kz.vainshtein.raftscore.discipline.entities;

import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A named discipline belonging to one competition. */
@Entity
@Table(name = "disciplines")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discipline extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false, updatable = false)
    private Competition competition;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "TEXT")
    private DisciplineType type;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Builder
    private Discipline(Competition competition, String name, DisciplineType type, int displayOrder) {
        this.competition = Objects.requireNonNull(competition, "competition");
        setName(name);
        setType(type);
        setDisplayOrder(displayOrder);
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    public void setType(DisciplineType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public void setDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder must not be negative");
        }
        this.displayOrder = displayOrder;
    }
}
