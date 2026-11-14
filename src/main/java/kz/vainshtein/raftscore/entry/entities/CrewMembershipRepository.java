package kz.vainshtein.raftscore.entry.entities;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewMembershipRepository extends JpaRepository<CrewMembership, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"participant", "crewEntry"})
    List<CrewMembership> findByCrewEntryIdInOrderByPositionAsc(java.util.Collection<Long> ids);
    List<CrewMembership> findByCrewEntryIdOrderByPositionAsc(Long crewEntryId);
}
