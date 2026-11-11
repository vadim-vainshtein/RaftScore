package kz.vainshtein.raftscore.entry.entities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewRequirementValueRepository extends JpaRepository<CrewRequirementValue, Long> {
    List<CrewRequirementValue> findByCrewEntryId(Long crewEntryId);
}
