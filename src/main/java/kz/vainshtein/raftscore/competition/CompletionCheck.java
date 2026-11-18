package kz.vainshtein.raftscore.competition;

import java.util.ArrayList;
import java.util.List;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.entry.services.CrewEligibilityService;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Explains all outstanding work before a discipline or competition can be closed. */
@Service
@RequiredArgsConstructor
public class CompletionCheck {
    private final DisciplineRepository disciplines;
    private final CrewEntryRepository crews;
    private final CrewEligibilityService eligibility;
    private final StartListRepository lists;
    private final StartSlotRepository slots;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository slalom;
    private final SlalomGatePenaltyRepository penalties;
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;
    private final kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository calculated;

    public record Issue(Long disciplineId, String discipline, Long categoryId, String category, Long crewId, String crew, String code, Integer attempt, Integer round, Integer heat) { }

    public List<Issue> competition(long competitionId) {
        var configured = disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(competitionId);
        if (configured.isEmpty()) return List.of(new Issue(null, null, null, null, null, null, "noDisciplines", null, null, null));
        var categories = configured.stream().flatMap(d -> d.getCategories().stream()).map(c -> c.getId()).distinct().toList();
        if (categories.isEmpty()) return List.of(new Issue(null, null, null, null, null, null, "noParticipatingCategories", null, null, null));
        var issues = new ArrayList<Issue>();
        if (categories.stream().flatMap(id -> crews.findByCompetitionCategoryId(id).stream()).noneMatch(eligibility::isEligible))
            issues.add(new Issue(null, null, null, null, null, null, "noEligibleCrews", null, null, null));
        configured.forEach(d -> issues.addAll(discipline(d)));
        return List.copyOf(issues);
    }

    public List<Issue> discipline(Discipline discipline) {
        return discipline(discipline, null);
    }

    public List<Issue> discipline(Discipline discipline, Long categoryId) {
        var issues = new ArrayList<Issue>();
        for (var category : discipline.getCategories()) {
            if (categoryId != null && !category.getId().equals(categoryId)) continue;
            var entrants = crews.findByCompetitionCategoryId(category.getId()).stream().filter(eligibility::isEligible).toList();
            if (entrants.isEmpty() && discipline.getType() != DisciplineType.PARALLEL_SPRINT) continue;
            if (discipline.getType() == DisciplineType.PARALLEL_SPRINT) {
                var bracket = brackets.findByDisciplineIdAndCategoryId(discipline.getId(), category.getId());
                if (entrants.isEmpty() && bracket.isEmpty()) continue;
                var qualification = discipline.getQualificationDiscipline();
                if (qualification == null) qualification = disciplines.findByCompetitionIdAndTypeOrderByDisplayOrderAscIdAsc(discipline.getCompetition().getId(), DisciplineType.SPRINT).stream()
                        .filter(d -> d.getCategories().stream().anyMatch(c -> c.getId().equals(category.getId()))).findFirst().orElse(null);
                boolean bracketRequired = false;
                java.util.List<CrewEntry> qualifiedCrews = null;
                if (qualification == null) {
                    issues.add(issue(discipline, category, null, "noQualification", null, null, null));
                } else if (!discipline(qualification, category.getId()).isEmpty()) {
                    issues.add(issue(discipline, category, null, "qualificationIncomplete", null, null, null));
                } else {
                    qualifiedCrews = calculated.findByDisciplineIdAndCrewEntryCompetitionCategoryId(qualification.getId(), category.getId()).stream()
                            .filter(r -> r.getStatus() == kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus.FINISHED && r.getPlace() != null && eligibility.isEligible(r.getCrewEntry()))
                            .sorted(java.util.Comparator.comparing(kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResult::getPlace)
                                    .thenComparing(r -> r.getCrewEntry().getStartNumber()).thenComparing(r -> r.getCrewEntry().getId()))
                            .map(r -> r.getCrewEntry()).toList();
                    bracketRequired = qualifiedCrews.size() >= 2;
                }
                if (bracket.isEmpty()) {
                    if (bracketRequired) issues.add(issue(discipline, category, null, "noBracket", null, null, null));
                } else {
                    var allHeats = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.get().getId());
                    if (qualifiedCrews != null) {
                        var seededIds = allHeats.stream().filter(h -> h.getRoundNumber() == 1)
                                .flatMap(h -> java.util.stream.Stream.of(h.getCrew1(), h.getCrew2())).filter(java.util.Objects::nonNull)
                                .map(CrewEntry::getId).collect(java.util.stream.Collectors.toSet());
                        var qualifiedIds = qualifiedCrews.stream().map(CrewEntry::getId).collect(java.util.stream.Collectors.toSet());
                        for (var crew : qualifiedCrews) if (!seededIds.contains(crew.getId()))
                            issues.add(issue(discipline, category, crew, "missingSeededCrew", null, null, null));
                        for (var heat : allHeats) if (heat.getRoundNumber() == 1)
                            for (var crew : java.util.stream.Stream.of(heat.getCrew1(), heat.getCrew2()).filter(java.util.Objects::nonNull).toList())
                                if (!qualifiedIds.contains(crew.getId())) issues.add(issue(discipline, category, crew, "extraSeededCrew", null, null, null));
                    }
                    if (qualifiedCrews != null && qualifiedCrews.size() >= 2 && !sameSeeding(bracket.get(), allHeats, qualifiedCrews))
                        issues.add(issue(discipline, category, null, "seedOrderChanged", null, null, null));
                    if (allHeats.isEmpty()) issues.add(issue(discipline, category, null, "emptyBracket", null, null, null));
                    long entrantsInBracket = allHeats.stream().filter(h -> h.getRoundNumber() == 1)
                            .mapToLong(h -> (h.getCrew1() == null ? 0 : 1) + (h.getCrew2() == null ? 0 : 1)).sum();
                    int finalRound = allHeats.stream().mapToInt(ParallelSprintHeat::getRoundNumber).max().orElse(0);
                    if (entrantsInBracket >= 4 && allHeats.stream().noneMatch(h -> h.getRoundNumber() == finalRound && h.getHeatNumber() == 2))
                        issues.add(issue(discipline, category, null, "missingBronze", null, null, null));
                    for (var heat : allHeats) {
                        if (!heat.outcomeResolved())
                            issues.add(issue(discipline, category, null, "unfinishedHeat", null, heat.getRoundNumber(), heat.getHeatNumber()));
                    }
                }
                continue;
            }
            var list = lists.findByDisciplineIdAndCategoryId(discipline.getId(), category.getId());
            var assigned = list.map(l -> slots.findByStartListIdOrderByPositionAsc(l.getId())).orElseGet(List::of);
            var results = attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(discipline.getId(), category.getId());
            var config = discipline.getType() == DisciplineType.SLALOM ? slalom.findByDisciplineId(discipline.getId()).orElseThrow() : null;
            for (var crew : entrants) {
                if (assigned.stream().noneMatch(s -> s.getCrew().getId().equals(crew.getId()))) {
                    issues.add(issue(discipline, category, crew, "missingStart", null, null, null));
                    continue;
                }
                var own = results.stream().filter(a -> a.getCrewEntry().getId().equals(crew.getId())).toList();
                boolean disqualified = own.stream().anyMatch(a -> a.getStatus() == AttemptResultStatus.DSQ_DISCIPLINE || a.getStatus() == AttemptResultStatus.DSQ_COMPETITION);
                int count = config == null ? 1 : config.getAttemptCount();
                for (int number = 1; number <= count && !disqualified; number++) {
                    final int attemptNumber = number;
                    var attempt = own.stream().filter(a -> a.getAttemptNumber() == attemptNumber).findFirst();
                    if (attempt.isEmpty()) issues.add(issue(discipline, category, crew, "missingAttempt", number, null, null));
                    else if (config != null && attempt.get().getStatus() == AttemptResultStatus.FINISHED && penalties.findByAttemptId(attempt.get().getId()).size() != config.getGateCount())
                        issues.add(issue(discipline, category, crew, "missingGates", number, null, null));
                }
            }
        }
        return List.copyOf(issues);
    }

    private boolean sameSeeding(ParallelSprintBracket bracket, List<ParallelSprintHeat> heats, List<CrewEntry> qualified) {
        var current = qualified.stream().map(CrewEntry::getId).toList();
        var currentSet = new java.util.HashSet<>(current);
        var original = bracket.getSeedOrder();
        if (original != null && !original.stream().filter(currentSet::contains).toList().equals(current)) return false;
        var expectedOrder = original == null ? current : original.stream().map(id -> currentSet.contains(id) ? id : null).toList();
        var expected = kz.vainshtein.raftscore.bracket.ParallelSprintDraw.firstRound(expectedOrder);
        var firstRound = heats.stream().filter(h -> h.getRoundNumber() == 1)
                .sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        if (firstRound.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            var heat = firstRound.get(i); var pair = expected.get(i);
            if (heat.getHeatNumber() != i + 1 || !java.util.Objects.equals(id(heat.getCrew1()), pair.first())
                    || !java.util.Objects.equals(id(heat.getCrew2()), pair.second())) return false;
        }
        return true;
    }

    private Long id(CrewEntry crew) { return crew == null ? null : crew.getId(); }
    private Issue issue(Discipline discipline, kz.vainshtein.raftscore.competition.entities.CompetitionCategory category, CrewEntry crew, String code, Integer attempt, Integer round, Integer heat) {
        return new Issue(discipline.getId(), discipline.getName(), category.getId(), category.getDisplayName(),
                crew == null ? null : crew.getId(), crew == null ? null : crew.getCrewName(), code, attempt, round, heat);
    }
}
