package kz.vainshtein.raftscore.competition.entities;

import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for competition entry requirements. */
public interface EntryRequirementRepository extends JpaRepository<EntryRequirement, Long> {
}
