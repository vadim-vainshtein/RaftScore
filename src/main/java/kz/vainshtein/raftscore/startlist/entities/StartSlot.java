package kz.vainshtein.raftscore.startlist.entities;
import java.time.Instant;
import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.*;
@Entity @Table(name = "start_slots") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StartSlot extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "start_list_id", nullable = false, updatable = false) private StartList startList;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false, updatable = false) private CompetitionCategory category;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "crew_entry_id", nullable = false, updatable = false) private CrewEntry crew;
    @Column(nullable = false) private int position;
    @Column(name = "planned_time") private Instant plannedTime;
    @Builder private StartSlot(StartList startList, CrewEntry crew, int position, Instant plannedTime) {
        this.startList = Objects.requireNonNull(startList, "startList");
        this.crew = Objects.requireNonNull(crew, "crew");
        this.category = startList.getCategory();
        var crewCategory = crew.getCompetitionCategory();
        if (category != crewCategory && (category.getId() == null || !category.getId().equals(crewCategory.getId())))
            throw new IllegalArgumentException("crew must belong to the start list category");
        if (position <= 0) throw new IllegalArgumentException("position must be positive");
        this.position = position;
        this.plannedTime = plannedTime;
    }
}
