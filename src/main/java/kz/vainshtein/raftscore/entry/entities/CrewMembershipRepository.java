package kz.vainshtein.raftscore.entry.entities;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMembershipRepository extends JpaRepository<CrewMembership, Long> {
    List<CrewMembership> findByCrewEntryIdOrderByPositionAsc(Long crewEntryId);
}
