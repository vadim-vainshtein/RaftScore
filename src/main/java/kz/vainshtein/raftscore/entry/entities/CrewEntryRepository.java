package kz.vainshtein.raftscore.entry.entities;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrewEntryRepository extends JpaRepository<CrewEntry, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"competitionCategory", "competitionCategory.competition"})
    @org.springframework.data.jpa.repository.Query("select e from CrewEntry e where locate(lower(:name), lower(e.crewName)) > 0 order by e.competitionCategory.competition.startDate desc, e.id desc")
    java.util.List<CrewEntry> searchByName(@org.springframework.data.repository.query.Param("name") String name);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"competitionCategory", "competitionCategory.competition"})
    @org.springframework.data.jpa.repository.Query("select e from CrewEntry e, CompetitionCategory target where target.id = :categoryId and e.competitionCategory.classLabel = target.classLabel and e.competitionCategory.scoringGroupLabel = target.scoringGroupLabel order by e.registeredAt desc nulls last, e.id desc")
    java.util.List<CrewEntry> findHistoryForCategory(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
    java.util.List<CrewEntry> findByCompetitionCategoryCompetitionId(Long competitionId);
    java.util.List<CrewEntry> findByCompetitionCategoryId(Long categoryId);
    Optional<CrewEntry> findByIdAndCompetitionCategoryCompetitionId(Long id, Long competitionId);
}
