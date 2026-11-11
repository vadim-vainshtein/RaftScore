package kz.vainshtein.raftscore.bracket.entities;

import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import lombok.*;

/** One heat; null competitors represent unfilled slots or a bye. */
@Entity
@Table(name = "parallel_sprint_heats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParallelSprintHeat extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bracket_id", nullable = false, updatable = false) private ParallelSprintBracket bracket;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false, updatable = false) private CompetitionCategory category;
    @Column(name = "round_number", nullable = false, updatable = false) private int roundNumber;
    @Column(name = "heat_number", nullable = false, updatable = false) private int heatNumber;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "crew_1_id") private CrewEntry crew1;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "crew_2_id") private CrewEntry crew2;
    @Column(name = "time_1_centiseconds") private Integer time1Centiseconds;
    @Column(name = "time_2_centiseconds") private Integer time2Centiseconds;
    @Enumerated(EnumType.STRING) @Column(name = "status_1", columnDefinition = "TEXT") private AttemptResultStatus status1;
    @Enumerated(EnumType.STRING) @Column(name = "status_2", columnDefinition = "TEXT") private AttemptResultStatus status2;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "next_heat_id") private ParallelSprintHeat nextHeat;
    @Column(name = "next_slot") private Integer nextSlot;
    @Column(name = "winner_slot") private Integer winnerSlot;
    @Column(name = "winner_overridden", nullable = false) private boolean winnerOverridden;

    @Builder
    private ParallelSprintHeat(ParallelSprintBracket bracket, int roundNumber, int heatNumber, CrewEntry crew1, CrewEntry crew2,
                               Integer time1Centiseconds, Integer time2Centiseconds, AttemptResultStatus status1, AttemptResultStatus status2,
                               ParallelSprintHeat nextHeat, Integer nextSlot, Integer winnerSlot) {
        this.bracket = Objects.requireNonNull(bracket, "bracket");
        this.category = bracket.getCategory();
        if (roundNumber <= 0 || heatNumber <= 0) throw new IllegalArgumentException("round and heat numbers must be positive");
        this.roundNumber = roundNumber;
        this.heatNumber = heatNumber;
        this.crew1 = crew1;
        this.crew2 = crew2;
        this.time1Centiseconds = time1Centiseconds;
        this.time2Centiseconds = time2Centiseconds;
        this.status1 = status1;
        this.status2 = status2;
        if ((nextHeat == null) != (nextSlot == null) || (nextSlot != null && nextSlot != 1 && nextSlot != 2)) throw new IllegalArgumentException("next heat and slot must be specified together");
        if (nextHeat != null && (nextHeat.getBracket() != bracket || nextHeat.getRoundNumber() != roundNumber + 1)) throw new IllegalArgumentException("next heat must belong to the next round of the same bracket");
        if (winnerSlot != null && !((winnerSlot == 1 && crew1 != null) || (winnerSlot == 2 && crew2 != null))) throw new IllegalArgumentException("winner must occupy a slot");
        this.winnerSlot = winnerSlot;
        this.nextHeat = nextHeat;
        this.nextSlot = nextSlot;
    }
    /** Records an already validated result. */
    public void record(AttemptResultStatus firstStatus, Integer firstTime, AttemptResultStatus secondStatus, Integer secondTime, Integer winner, boolean overridden) {
        this.status1 = firstStatus;
        this.time1Centiseconds = firstTime;
        this.status2 = secondStatus;
        this.time2Centiseconds = secondTime;
        this.winnerSlot = winner;
        this.winnerOverridden = overridden;
    }

    /** Changes only the feeder's own slot, before this heat has been played. */
    public void advance(int slot, CrewEntry previousWinner, CrewEntry winner) {
        if (winnerSlot != null || status1 != null || status2 != null || time1Centiseconds != null || time2Centiseconds != null)
            throw new IllegalStateException("downstream heat has already been played");
        var existing = slot == 1 ? crew1 : crew2;
        if (slot != 1 && slot != 2) throw new IllegalArgumentException("invalid destination slot");
        if (existing != null && (previousWinner == null || !existing.getId().equals(previousWinner.getId())))
            throw new IllegalStateException("downstream slot is already occupied");
        if (slot == 1) crew1 = winner; else crew2 = winner;
    }
}
