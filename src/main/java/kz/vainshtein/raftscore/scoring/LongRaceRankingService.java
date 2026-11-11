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
public class LongRaceRankingService {
    private final StartListRepository startLists;
    private final StartSlotRepository startSlots;
    private final AttemptResultRepository attempts;

    @Transactional(readOnly = true)
    public List<LongRaceRanking.Result> rank(Long startListId) {
        var list = startLists.findById(startListId).orElseThrow();
        if (list.getDiscipline().getType() != DisciplineType.LONG_RACE)
            throw new IllegalArgumentException("start list must belong to a long race");
        return LongRaceRanking.rank(startSlots.findByStartListIdOrderByPositionAsc(startListId),
                attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(
                        list.getDiscipline().getId(), list.getCategory().getId()));
    }
}
