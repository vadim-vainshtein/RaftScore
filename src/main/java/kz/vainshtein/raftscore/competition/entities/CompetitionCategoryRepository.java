package kz.vainshtein.raftscore.competition.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for competition categories. */
public interface CompetitionCategoryRepository extends JpaRepository<CompetitionCategory, Long> {
}
