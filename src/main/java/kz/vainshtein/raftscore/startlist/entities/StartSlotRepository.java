package kz.vainshtein.raftscore.startlist.entities;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface StartSlotRepository extends JpaRepository<StartSlot, Long> {
    void deleteByStartListId(Long startListId);
    List<StartSlot> findByStartListIdOrderByPositionAsc(Long startListId);
}
