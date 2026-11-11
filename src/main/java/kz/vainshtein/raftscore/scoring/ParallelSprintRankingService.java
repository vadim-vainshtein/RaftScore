package kz.vainshtein.raftscore.scoring;

import java.util.List;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracketRepository;
import kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParallelSprintRankingService {
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;

    @Transactional(readOnly = true)
    public List<ParallelSprintRanking.Result> rank(long bracketId) {
        brackets.findById(bracketId).orElseThrow();
        return ParallelSprintRanking.rank(heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracketId));
    }
}
