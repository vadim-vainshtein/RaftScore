package kz.vainshtein.raftscore.bracket.entities;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ParallelSprintHeatRepository extends JpaRepository<ParallelSprintHeat, Long> {
    List<ParallelSprintHeat> findByBracketIdOrderByRoundNumberAscHeatNumberAsc(Long bracketId);
}
