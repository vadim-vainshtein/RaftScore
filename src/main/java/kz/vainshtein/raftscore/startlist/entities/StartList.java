package kz.vainshtein.raftscore.startlist.entities;
import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.*;
@Entity @Table(name = "start_lists") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StartList extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "discipline_id", nullable = false, updatable = false) private Discipline discipline;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false, updatable = false) private CompetitionCategory category;
    @Builder private StartList(Discipline discipline, CompetitionCategory category) {
        this.discipline = Objects.requireNonNull(discipline, "discipline");
        this.category = Objects.requireNonNull(category, "category");
    }
}
