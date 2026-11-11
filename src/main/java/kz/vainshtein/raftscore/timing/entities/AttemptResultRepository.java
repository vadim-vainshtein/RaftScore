package kz.vainshtein.raftscore.timing.entities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptResultRepository extends JpaRepository<AttemptResult, Long> {
    java.util.Optional<AttemptResult> findByDisciplineIdAndCrewEntryIdAndAttemptNumber(Long disciplineId, Long crewId, int attemptNumber);
    List<AttemptResult> findByDisciplineIdAndCrewEntryCompetitionCategoryId(Long disciplineId, Long categoryId);
}
