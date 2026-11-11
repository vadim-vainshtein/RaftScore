package kz.vainshtein.raftscore.competition.entities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Storage access for competition entry requirements. */
public interface EntryRequirementRepository extends JpaRepository<EntryRequirement, Long> {
    List<EntryRequirement> findByCompetitionId(Long competitionId);
}
