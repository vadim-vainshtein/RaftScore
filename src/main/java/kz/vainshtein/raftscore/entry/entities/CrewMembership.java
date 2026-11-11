package kz.vainshtein.raftscore.entry.entities;

import java.util.Objects;

import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A participant's position in a registered crew. */
@Entity
@Table(name = "crew_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrewMembership extends VersionedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "crew_entry_id", nullable = false)
    private CrewEntry crewEntry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "competition_category_id", nullable = false, updatable = false)
    private CompetitionCategory competitionCategory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(nullable = false)
    private int position;

    @Builder
    private CrewMembership(CrewEntry crewEntry, Participant participant, int position) {
        this.crewEntry = Objects.requireNonNull(crewEntry, "crewEntry");
        this.competitionCategory = crewEntry.getCompetitionCategory();
        this.participant = Objects.requireNonNull(participant, "participant");
        changePosition(position);
    }

    public void changePosition(int position) {
        if (position <= 0) {
            throw new IllegalArgumentException("position must be positive");
        }
        this.position = position;
    }
}
