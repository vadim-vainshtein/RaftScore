package kz.vainshtein.raftscore.discipline.entities;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import kz.vainshtein.raftscore.scoring.entities.PlacementPointsTable;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "points_table_id")
    private PlacementPointsTable pointsTable;

    public void setPointsTable(PlacementPointsTable table) {
        if (table != null) {
            var owner = table.getCompetition();
            if (owner != competition && (competition.getId() == null
                    || !competition.getId().equals(owner.getId()))) {
                throw new IllegalArgumentException("points table must belong to the discipline competition");
            }
        }
        this.pointsTable = table;
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "qualification_discipline_id")
    private Discipline qualificationDiscipline;

    public void setQualificationDiscipline(Discipline qualification) {
        this.qualificationDiscipline = qualification;
    }

    @ManyToMany
    @JoinTable(name = "discipline_categories",
            joinColumns = @JoinColumn(name = "discipline_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    @Getter(AccessLevel.NONE)
    private Set<CompetitionCategory> categories = new HashSet<>();

    public Set<CompetitionCategory> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    /** Replaces the participating categories after validating the entire selection. */
    public void replaceCategories(Collection<CompetitionCategory> selection) {
        var replacement = new HashSet<>(Objects.requireNonNull(selection, "selection"));
        for (var category : replacement) {
            Objects.requireNonNull(category, "category");
            var owner = category.getCompetition();
            if (owner != competition && (competition.getId() == null
                    || !competition.getId().equals(owner.getId()))) {
                throw new IllegalArgumentException("category must belong to the discipline's competition");
            }
        }
        categories.retainAll(replacement);
        categories.addAll(replacement);
    }
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
