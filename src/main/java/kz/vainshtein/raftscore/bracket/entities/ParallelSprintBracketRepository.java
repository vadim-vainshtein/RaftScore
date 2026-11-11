package kz.vainshtein.raftscore.bracket.entities;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ParallelSprintBracketRepository extends JpaRepository<ParallelSprintBracket, Long> { boolean existsByDisciplineIdAndCategoryId(Long disciplineId, Long categoryId); }
