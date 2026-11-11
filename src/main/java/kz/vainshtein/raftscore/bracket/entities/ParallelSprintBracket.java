package kz.vainshtein.raftscore.bracket.entities;

import java.util.Objects;
import jakarta.persistence.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.discipline.entities.Discipline;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.persistence.VersionedEntity;
import lombok.*;

@Entity
@Table(name = "parallel_sprint_brackets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParallelSprintBracket extends VersionedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "discipline_id", nullable = false, updatable = false) private Discipline discipline;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false, updatable = false) private CompetitionCategory category;

    @Builder
    private ParallelSprintBracket(Discipline discipline, CompetitionCategory category) {
        this.discipline = Objects.requireNonNull(discipline, "discipline");
        this.category = Objects.requireNonNull(category, "category");
        if (discipline.getType() != DisciplineType.PARALLEL_SPRINT) throw new IllegalArgumentException("discipline must be parallel sprint");
    }
}
