package kz.vainshtein.raftscore.competition.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for competition categories. */
public interface CompetitionCategoryRepository extends JpaRepository<CompetitionCategory, Long> {
    java.util.List<CompetitionCategory> findByCompetitionId(Long competitionId);
    Optional<CompetitionCategory> findByIdAndCompetitionId(Long id, Long competitionId);
}
