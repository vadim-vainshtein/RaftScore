package kz.vainshtein.raftscore.slalom.entities;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlalomGatePenaltyRepository extends JpaRepository<SlalomGatePenalty, Long> { void deleteByAttemptId(Long attemptId); List<SlalomGatePenalty> findByAttemptId(Long attemptId); List<SlalomGatePenalty> findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(Long configurationId, Long categoryId); }
