package kz.vainshtein.raftscore.scoring.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Points awarded for a particular finishing place. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlacementPointsRow {
    @Column(name = "place", nullable = false)
    private int place;

    @Column(name = "points", nullable = false)
    private int points;

    public PlacementPointsRow(int place, int points) {
        if (place <= 0 || points < 0) {
            throw new IllegalArgumentException("place must be positive and points must be nonnegative");
        }
        this.place = place;
        this.points = points;
    }
}
