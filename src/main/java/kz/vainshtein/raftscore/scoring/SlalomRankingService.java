package kz.vainshtein.raftscore.scoring;

import java.util.List;
import kz.vainshtein.raftscore.discipline.entities.DisciplineType;
import kz.vainshtein.raftscore.slalom.entities.SlalomConfigurationRepository;
import kz.vainshtein.raftscore.slalom.entities.SlalomGatePenaltyRepository;
import kz.vainshtein.raftscore.startlist.entities.StartListRepository;
import kz.vainshtein.raftscore.startlist.entities.StartSlotRepository;
import kz.vainshtein.raftscore.timing.entities.AttemptResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlalomRankingService {
    private final StartListRepository startLists;
    private final StartSlotRepository startSlots;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository configurations;
    private final SlalomGatePenaltyRepository penalties;

    @Transactional(readOnly = true)
    public List<SlalomRanking.Result> rank(Long startListId) {
        var list = startLists.findById(startListId).orElseThrow();
        if (list.getDiscipline().getType() != DisciplineType.SLALOM)
            throw new IllegalArgumentException("start list must belong to a slalom");
        var disciplineId = list.getDiscipline().getId();
        var configuration = configurations.findByDisciplineId(disciplineId).orElseThrow();
        return SlalomRanking.rank(configuration, startSlots.findByStartListIdOrderByPositionAsc(startListId),
                attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(disciplineId, list.getCategory().getId()),
                penalties.findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(configuration.getId(), list.getCategory().getId()));
    }
}
