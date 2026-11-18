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
    private final kz.vainshtein.raftscore.competition.CompletionCheck completionCheck;
    private final CompetitionCategoryRepository categories;
    private final CalculatedDisciplineResultRepository results;
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entityManager;
    private final kz.vainshtein.raftscore.scoring.RecalculationService recalculation;

    public static class RecordedHeats extends IllegalStateException { }

    public static class QualificationIncomplete extends IllegalStateException {
        QualificationIncomplete() { super("qualification results are incomplete for this category"); }
    }

    /** Explicitly upgrades a legacy bracket without changing any existing heat. */
    @Transactional
    public ParallelSprintBracket addBronzeFinal(long disciplineId, long categoryId) {
        var discipline = disciplines.findForStartListUpdate(disciplineId).orElseThrow();
        discipline.requireStarted();
        var bracket = brackets.findByDisciplineIdAndCategoryId(disciplineId, categoryId).orElseThrow();
        var all = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId());
        int finalRound = all.stream().mapToInt(ParallelSprintHeat::getRoundNumber).max().orElseThrow();
        var finals = all.stream().filter(h -> h.getRoundNumber() == finalRound).toList();
        if (finals.size() != 1 || finals.getFirst().getHeatNumber() != 1)
            throw new IllegalStateException("bracket already has a bronze final or invalid finals");
        var finalA = finals.getFirst();
        var semifinals = all.stream().filter(h -> h.getNextHeat() != null && h.getNextHeat().getId().equals(finalA.getId()))
                .sorted(Comparator.comparingInt(ParallelSprintHeat::getNextSlot)).toList();
        long entrants = all.stream().filter(h -> h.getRoundNumber() == 1)
                .mapToLong(h -> (h.getCrew1() == null ? 0 : 1) + (h.getCrew2() == null ? 0 : 1)).sum();
        if (entrants < 4 || semifinals.size() != 2 || semifinals.get(0).getNextSlot() != 1 || semifinals.get(1).getNextSlot() != 2)
            throw new IllegalStateException("bracket does not have two semifinals");
        var bronze = ParallelSprintHeat.builder().bracket(bracket).roundNumber(finalRound).heatNumber(2).build();
        for (var semifinal : semifinals)
            bronze.replaceWithdrawnFeeder(semifinal.getNextSlot(), null, loser(semifinal), semifinal.outcomeResolved());
        heats.saveAndFlush(bronze);
        recalculation.recalculateLocked(discipline, categoryId);
        return bracket;
    }

    private CrewEntry loser(ParallelSprintHeat heat) {
        if (heat.getWinnerSlot() == null) return null;
        return heat.getWinnerSlot() == 1 ? heat.getCrew2() : heat.getCrew1();
    }
    private final kz.vainshtein.raftscore.entry.services.CrewEligibilityService eligibility;

    /** A null qualificationId selects the first configured ordinary sprint. */
    @Transactional
    public ParallelSprintBracket seed(long disciplineId, long categoryId, Long qualificationId) {
        var discipline = disciplines.findForStartListUpdate(disciplineId).orElseThrow();
        discipline.requireStarted();
        var ranked = qualifiedCrews(discipline, categoryId, qualificationId);
        if (ranked.size() < 2) throw new IllegalArgumentException("at least two ranked finishers are required");
        if (brackets.existsByDisciplineIdAndCategoryId(disciplineId, categoryId))
            throw new IllegalStateException("bracket already exists");
        var category = categories.findById(categoryId).orElseThrow();
        var bracket = brackets.save(ParallelSprintBracket.builder().discipline(discipline).category(category).seedOrder(ranked.stream().map(CrewEntry::getId).toList()).build());
        createHeats(bracket, ranked);
        return bracket;
    }


    /** Replaces only an unplayed bracket; recorded heats must first be annulled explicitly. */
    @Transactional
    public ParallelSprintBracket rebuild(long disciplineId, long categoryId, long expectedBracketId) {
        var discipline = disciplines.findForStartListUpdate(disciplineId).orElseThrow();
        discipline.requireStarted();
        var bracket = brackets.findByDisciplineIdAndCategoryId(disciplineId, categoryId).orElseThrow(() -> new IllegalStateException("bracket has changed"));
        if (!bracket.getId().equals(expectedBracketId)) throw new IllegalStateException("bracket has changed");
        var all = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId());
        if (all.stream().anyMatch(h -> h.getStatus1() != null || h.getStatus2() != null || h.isWinnerOverridden()
                || h.getTime1Centiseconds() != null || h.getTime2Centiseconds() != null)) throw new RecordedHeats();
        var ranked = qualifiedCrews(discipline, categoryId, null);
        entityManager.flush();
        jdbc.update("delete from parallel_sprint_heats where bracket_id = ?", bracket.getId());
        jdbc.update("delete from parallel_sprint_brackets where id = ?", bracket.getId());
        ParallelSprintBracket replacement = null;
        if (ranked.size() >= 2) {
            var category = categories.findById(categoryId).orElseThrow();
            replacement = brackets.save(ParallelSprintBracket.builder().discipline(discipline).category(category).seedOrder(ranked.stream().map(CrewEntry::getId).toList()).build());
            createHeats(replacement, ranked);
        }
        recalculation.recalculateLocked(discipline, categoryId);
        return replacement;
    }

    private List<CrewEntry> qualifiedCrews(Discipline discipline, long categoryId, Long qualificationId) {
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
        if (qualification.getId().equals(discipline.getId()) ||
                !qualification.getCompetition().getId().equals(discipline.getCompetition().getId()) ||
                qualification.getCategories().stream().noneMatch(c -> c.getId().equals(categoryId))) {
            throw new IllegalArgumentException("qualification must be another discipline in the same competition and category");
        }
        if (!completionCheck.discipline(qualification, categoryId).isEmpty())
            throw new QualificationIncomplete();
        var ranked = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(qualification.getId(), categoryId).stream()
                .filter(r -> r.getStatus() == CalculatedResultStatus.FINISHED && r.getPlace() != null && eligibility.isEligible(r.getCrewEntry()))
                .sorted(Comparator.comparing(CalculatedDisciplineResult::getPlace)
                        .thenComparing(r -> r.getCrewEntry().getStartNumber())
                        .thenComparing(r -> r.getCrewEntry().getId()))
                .map(CalculatedDisciplineResult::getCrewEntry).toList();
        return ranked;
    }
    private void createHeats(ParallelSprintBracket bracket, List<CrewEntry> ranked) {
        int size = 2;
        while (size < ranked.size()) size = Math.multiplyExact(size, 2);
        var firstRound = ParallelSprintDraw.firstRound(ranked);
        int rounds = Integer.numberOfTrailingZeros(size);
        List<ParallelSprintHeat> next = List.of();
        if (ranked.size() >= 4) {
            heats.save(ParallelSprintHeat.builder().bracket(bracket).roundNumber(rounds).heatNumber(2).build());
        }
        for (int round = rounds; round >= 1; round--) {
            int count = size >> round;
            var current = new ArrayList<ParallelSprintHeat>();
            for (int i = 0; i < count; i++) {
                CrewEntry one = null, two = null;
                if (round == 1) { one = firstRound.get(i).first(); two = firstRound.get(i).second(); }
                if (round == 2) { one = byeWinner(firstRound.get(i * 2)); two = byeWinner(firstRound.get(i * 2 + 1)); }
                var builder = ParallelSprintHeat.builder().bracket(bracket).roundNumber(round).heatNumber(i + 1).crew1(one).crew2(two);
                if (round < rounds) builder.nextHeat(next.get(i / 2)).nextSlot(i % 2 + 1);
                if (round == 1 && (one == null ^ two == null)) builder.winnerSlot(one != null ? 1 : 2);
                current.add(heats.save(builder.build()));
            }
            next = current;
        }
    }

    private CrewEntry byeWinner(ParallelSprintDraw.Pair<CrewEntry> pair) { return pair.first() == null ? pair.second() : pair.second() == null ? pair.first() : null; }
}
