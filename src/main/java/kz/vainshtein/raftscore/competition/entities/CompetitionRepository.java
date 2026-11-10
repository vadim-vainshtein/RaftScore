package kz.vainshtein.raftscore.competition.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for competitions. */
public interface CompetitionRepository extends JpaRepository<Competition, Long> {
}
