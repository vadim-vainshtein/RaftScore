package kz.vainshtein.raftscore.protocol;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.standings.OverallStandingsService;
import kz.vainshtein.raftscore.timing.entities.*;
import kz.vainshtein.raftscore.bracket.entities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds document data while the competition lock is held. */
@Component
@RequiredArgsConstructor
class ProtocolSnapshot {
    private final CompetitionCategoryRepository categories;
    private final DisciplineRepository disciplines;
    private final CrewEntryRepository crews;
    private final CrewMembershipRepository memberships;
    private final CalculatedDisciplineResultRepository results;
    private final AttemptResultRepository attempts;
    private final SlalomConfigurationRepository configurations;
    private final SlalomGatePenaltyRepository penalties;
    private final OverallStandingsService standings;
    private final ParallelSprintBracketRepository brackets;
    private final ParallelSprintHeatRepository heats;

    record Document(String competition, String location, String timeZone, List<Category> categories) { }
    record Category(long id, String name, List<Crew> crews, List<DisciplineView> disciplines,
                    List<OverallStandingsService.Standing> standings) { }
    record Crew(long id, String name, String region, int number, List<String> members) { }
    record DisciplineView(long id, String name, String type, List<Result> results, List<Heat> heats) { }
    record Heat(int round, int number, Long crew1, Long crew2, Integer time1Centiseconds,
                Integer time2Centiseconds, String status1, String status2, Integer winnerSlot, boolean winnerOverridden) { }
    record Result(long crewId, Integer place, Long totalCentiseconds, String status, int points, List<Attempt> attempts) { }
    record Attempt(int number, Integer durationCentiseconds, Instant startInstant, Instant finishInstant,
                   int penaltyCentiseconds, String status, List<Gate> gates) { }
    record Gate(int number, int penaltySeconds) { }

    Document capture(Competition competition) {
        var ds = disciplines.findByCompetitionIdOrderByDisplayOrderAscIdAsc(competition.getId());
        var cs = categories.findByCompetitionId(competition.getId()).stream()
                .sorted(Comparator.comparing(CompetitionCategory::getId))
                .map(c -> category(c, ds)).toList();
        return new Document(competition.getTitle(), competition.getLocation(), competition.getTimeZone(), cs);
    }

    private Category category(CompetitionCategory category, List<Discipline> allDisciplines) {
        var entries = crews.findByCompetitionCategoryId(category.getId()).stream()
                .sorted(Comparator.comparing(CrewEntry::getStartNumber).thenComparing(CrewEntry::getId)).toList();
        var roster = entries.stream().map(c -> new Crew(c.getId(), c.getCrewName(), c.getRegionOrClub(), c.getStartNumber(),
                memberships.findByCrewEntryIdOrderByPositionAsc(c.getId()).stream().map(m -> {
                    var p = m.getParticipant();
                    return String.join(" ", java.util.stream.Stream.of(p.getSurname(), p.getGivenName(), p.getPatronymic())
                            .filter(s -> s != null && !s.isBlank()).toList());
                }).toList())).toList();
        var selected = allDisciplines.stream().filter(d -> d.getCategories().stream().anyMatch(c -> c.getId().equals(category.getId())))
                .map(d -> discipline(d, category.getId())).toList();
        return new Category(category.getId(), category.getDisplayName(), roster, selected, standings.standings(category.getId()));
    }

    private DisciplineView discipline(Discipline d, long categoryId) {
        var saved = attempts.findByDisciplineIdAndCrewEntryCompetitionCategoryId(d.getId(), categoryId);
        var gateRows = configurations.findByDisciplineId(d.getId())
                .map(c -> penalties.findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(c.getId(), categoryId))
                .orElse(List.of());
        var rows = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(d.getId(), categoryId).stream()
                .map(r -> new Result(r.getCrewEntry().getId(), r.getPlace(), r.getTotalCentiseconds(), r.getStatus().name(), r.getPoints(),
                        saved.stream().filter(a -> a.getCrewEntry().getId().equals(r.getCrewEntry().getId()))
                                .sorted(Comparator.comparingInt(AttemptResult::getAttemptNumber))
                                .map(a -> new Attempt(a.getAttemptNumber(), a.getDurationCentiseconds(), a.getStartInstant(), a.getFinishInstant(),
                                        a.getPenaltyCentiseconds(), a.getStatus().name(), gateRows.stream()
                                        .filter(g -> g.getAttempt().getId().equals(a.getId()))
                                        .sorted(Comparator.comparingInt(SlalomGatePenalty::getGateNumber))
                                        .map(g -> new Gate(g.getGateNumber(), g.getPenaltySeconds())).toList())).toList()))
                .sorted(Comparator.comparing(Result::place, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Result::crewId)).toList();
        var heatRows = brackets.findByDisciplineIdAndCategoryId(d.getId(), categoryId)
                .map(b -> heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(b.getId()).stream()
                        .map(h -> new Heat(h.getRoundNumber(), h.getHeatNumber(), h.getCrew1() == null ? null : h.getCrew1().getId(),
                                h.getCrew2() == null ? null : h.getCrew2().getId(), h.getTime1Centiseconds(), h.getTime2Centiseconds(),
                                h.getStatus1() == null ? null : h.getStatus1().name(), h.getStatus2() == null ? null : h.getStatus2().name(),
                                h.getWinnerSlot(), h.isWinnerOverridden())).toList()).orElse(List.of());
        return new DisciplineView(d.getId(), d.getName(), d.getType().name(), rows, heatRows);
    }
}
