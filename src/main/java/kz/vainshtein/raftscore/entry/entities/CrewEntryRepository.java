package kz.vainshtein.raftscore.entry.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewEntryRepository extends JpaRepository<CrewEntry, Long> {
    java.util.List<CrewEntry> findByCompetitionCategoryId(Long categoryId);
    Optional<CrewEntry> findByIdAndCompetitionCategoryCompetitionId(Long id, Long competitionId);
}
