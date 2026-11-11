package kz.vainshtein.raftscore.entry.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewEntryRepository extends JpaRepository<CrewEntry, Long> {
    Optional<CrewEntry> findByIdAndCompetitionCategoryCompetitionId(Long id, Long competitionId);
}
