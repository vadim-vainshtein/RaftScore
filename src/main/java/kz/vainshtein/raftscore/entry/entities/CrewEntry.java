package kz.vainshtein.raftscore.entry.entities;

import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A crew registered in a competition category. */
@Entity
@Table(name = "crew_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrewEntry extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_category_id", nullable = false)
    private CompetitionCategory competitionCategory;

    @Column(name = "crew_name", nullable = false, columnDefinition = "TEXT")
    private String crewName;

    @Column(name = "region_or_club", nullable = false, columnDefinition = "TEXT")
    private String regionOrClub;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String representative;

    @Column(name = "start_number", nullable = false)
    private int startNumber;

    @Column(name = "admission_decision")
    private Boolean admissionDecision;

    @Builder
    private CrewEntry(CompetitionCategory competitionCategory, String crewName, String regionOrClub,
                      String representative, int startNumber) {
        this.competitionCategory = Objects.requireNonNull(competitionCategory, "competitionCategory");
        updateDetails(crewName, regionOrClub, representative, startNumber);
    }

    public void updateDetails(String crewName, String regionOrClub, String representative, int startNumber) {
        var validatedName = requireText(crewName, "crewName");
        var validatedRegion = requireText(regionOrClub, "regionOrClub");
        var validatedRepresentative = requireText(representative, "representative");
        if (startNumber <= 0) {
            throw new IllegalArgumentException("startNumber must be positive");
        }
        this.crewName = validatedName;
        this.regionOrClub = validatedRegion;
        this.representative = validatedRepresentative;
        this.startNumber = startNumber;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
