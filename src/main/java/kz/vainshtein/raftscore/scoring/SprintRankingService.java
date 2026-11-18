package kz.vainshtein.raftscore.scoring;

import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.startlist.entities.StartListRepository;
import kz.vainshtein.raftscore.startlist.entities.StartSlotRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SprintRankingService {
    private final StartListRepository startLists;
    private final StartSlotRepository startSlots;
    private final AttemptResultRepository attempts;
    private final kz.vainshtein.raftscore.entry.services.CrewEligibilityPolicy eligibility;

    @Transactional(readOnly = true)
    public List<SprintRanking.Result> rank(Long startListId) {
        var list = startLists.findById(startListId).orElseThrow();
        if (list.getDiscipline().getType() != DisciplineType.SPRINT) {
            throw new IllegalArgumentException("start list must belong to a sprint");
        }
        var eligible = eligibility.eligibleIds(list.getCategory().getId());
        return SprintRanking.rank(startSlots.findByStartListIdOrderByPositionAsc(startListId),
                attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(
                        list.getDiscipline().getId(), list.getCategory().getId()).stream().filter(a -> eligible.contains(a.getCrewEntry().getId())).toList());
    }
}
