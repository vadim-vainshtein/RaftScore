package kz.vainshtein.raftscore.bracket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntry;
import kz.vainshtein.raftscore.scoring.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a single-elimination bracket from persisted qualification results. */
@Service
@RequiredArgsConstructor
public class ParallelSprintSeedingService {
    private final DisciplineRepository disciplines;
    private final CompetitionCategoryRepository categories;
    private final CalculatedDisciplineResultRepository results;
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;

    /** A null qualificationId selects the first configured ordinary sprint. */
    @Transactional
    public ParallelSprintBracket seed(long disciplineId, long categoryId, Long qualificationId) {
        var discipline = disciplines.findForStartListUpdate(disciplineId).orElseThrow();
        var category = categories.findById(categoryId).orElseThrow();
        if (discipline.getType() != DisciplineType.PARALLEL_SPRINT ||
                !discipline.getCompetition().getId().equals(category.getCompetition().getId()) ||
                discipline.getCategories().stream().noneMatch(c -> c.getId().equals(categoryId))) {
            throw new IllegalArgumentException("category must be selected for the parallel sprint competition");
        }
        if (qualificationId == null && discipline.getQualificationDiscipline() != null) {
            qualificationId = discipline.getQualificationDiscipline().getId();
        }
        var qualification = qualificationId == null
                ? disciplines.findByCompetitionIdAndTypeOrderByDisplayOrderAscIdAsc(discipline.getCompetition().getId(), DisciplineType.SPRINT)
                    .stream().filter(d -> d.getCategories().stream().anyMatch(c -> c.getId().equals(categoryId)))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("no qualifying sprint for category"))
                : disciplines.findById(qualificationId).orElseThrow();
        if (qualification.getId().equals(disciplineId) ||
                !qualification.getCompetition().getId().equals(discipline.getCompetition().getId()) ||
                qualification.getCategories().stream().noneMatch(c -> c.getId().equals(categoryId))) {
            throw new IllegalArgumentException("qualification must be another discipline in the same competition and category");
        }
        var ranked = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(qualification.getId(), categoryId).stream()
                .filter(r -> r.getStatus() == CalculatedResultStatus.FINISHED && r.getPlace() != null)
                .sorted(Comparator.comparing(CalculatedDisciplineResult::getPlace)
                        .thenComparing(r -> r.getCrewEntry().getStartNumber())
                        .thenComparing(r -> r.getCrewEntry().getId()))
                .map(CalculatedDisciplineResult::getCrewEntry).toList();
        if (ranked.size() < 2) throw new IllegalArgumentException("at least two ranked finishers are required");
        if (brackets.existsByDisciplineIdAndCategoryId(disciplineId, categoryId))
            throw new IllegalStateException("bracket already exists");
        var bracket = brackets.save(ParallelSprintBracket.builder().discipline(discipline).category(category).build());
        createHeats(bracket, ranked);
        return bracket;
    }


    private void createHeats(ParallelSprintBracket bracket, List<CrewEntry> ranked) {
        int size = 2;
        while (size < ranked.size()) size = Math.multiplyExact(size, 2);
        var positions = new ArrayList<>(List.of(1, 2));
        for (int width = 4; width <= size; width *= 2) {
            var expanded = new ArrayList<Integer>();
            for (var seed : positions) { expanded.add(seed); expanded.add(width + 1 - seed); }
            positions = expanded;
        }
        var firstRound = new ArrayList<CrewEntry[]>();
        for (int i = 0; i < size; i += 2) {
            firstRound.add(new CrewEntry[]{crewAt(positions.get(i), ranked), crewAt(positions.get(i + 1), ranked)});
        }
        int rounds = Integer.numberOfTrailingZeros(size);
        List<ParallelSprintHeat> next = List.of();
        for (int round = rounds; round >= 1; round--) {
            int count = size >> round;
            var current = new ArrayList<ParallelSprintHeat>();
            for (int i = 0; i < count; i++) {
                CrewEntry one = null, two = null;
                if (round == 1) { one = firstRound.get(i)[0]; two = firstRound.get(i)[1]; }
                if (round == 2) { one = byeWinner(firstRound.get(i * 2)); two = byeWinner(firstRound.get(i * 2 + 1)); }
                var builder = ParallelSprintHeat.builder().bracket(bracket).roundNumber(round).heatNumber(i + 1).crew1(one).crew2(two);
                if (round < rounds) builder.nextHeat(next.get(i / 2)).nextSlot(i % 2 + 1);
                if (round == 1 && (one == null ^ two == null)) builder.winnerSlot(one != null ? 1 : 2);
                current.add(heats.save(builder.build()));
            }
            next = current;
        }
    }
    private CrewEntry crewAt(int seed, List<CrewEntry> ranked) { return seed <= ranked.size() ? ranked.get(seed - 1) : null; }
    private CrewEntry byeWinner(CrewEntry[] pair) { return pair[0] == null ? pair[1] : pair[1] == null ? pair[0] : null; }
}
