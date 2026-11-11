package kz.vainshtein.raftscore.scoring.entities;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalculatedDisciplineResultRepository extends JpaRepository<CalculatedDisciplineResult, Long> {
    java.util.List<CalculatedDisciplineResult> findByDisciplineIdAndCrewEntryCompetitionCategoryId(Long disciplineId, Long categoryId);

    @org.springframework.data.jpa.repository.Query("select r from CalculatedDisciplineResult r join fetch r.crewEntry join fetch r.discipline d join d.categories c where c.id = :categoryId and r.crewEntry.competitionCategory.id = :categoryId order by d.displayOrder, d.id")
    java.util.List<CalculatedDisciplineResult> findCurrentByCategoryId(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}
