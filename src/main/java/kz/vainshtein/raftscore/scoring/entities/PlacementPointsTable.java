package kz.vainshtein.raftscore.scoring.entities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A competition-owned, reusable schedule of points by finishing place. */
@Entity
@Table(name = "placement_points_tables")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlacementPointsTable extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_id", nullable = false)
    private Competition competition;

    @Column(columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tie_policy", nullable = false, columnDefinition = "TEXT")
    private TiePointsPolicy tiePolicy;

    @ElementCollection
    @CollectionTable(name = "placement_points_rows", joinColumns = @JoinColumn(name = "table_id"))
    @OrderBy("place ASC")
    private List<PlacementPointsRow> rows = new ArrayList<>();

    public PlacementPointsTable(Competition competition, String name, TiePointsPolicy tiePolicy,
                                List<PlacementPointsRow> rows) {
        this.competition = Objects.requireNonNull(competition, "competition");
        replace(name, tiePolicy, rows);
    }

    /** Validates the complete replacement before changing the persisted aggregate. */
    public void replace(String name, TiePointsPolicy tiePolicy, List<PlacementPointsRow> rows) {
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(tiePolicy, "tiePolicy");
        Objects.requireNonNull(rows, "rows");
        var places = new HashSet<Integer>();
        var validated = new ArrayList<PlacementPointsRow>();
        for (var row : rows) {
            Objects.requireNonNull(row, "row");
            if (row.getPlace() <= 0 || row.getPoints() < 0 || !places.add(row.getPlace())) {
                throw new IllegalArgumentException("rows must have unique positive places and nonnegative points");
            }
            validated.add(row);
        }
        validated.sort(Comparator.comparingInt(PlacementPointsRow::getPlace));
        this.name = name;
        this.tiePolicy = tiePolicy;
        this.rows.clear();
        this.rows.addAll(validated);
    }

    public List<PlacementPointsRow> getRows() {
        return List.copyOf(rows);
    }
}
