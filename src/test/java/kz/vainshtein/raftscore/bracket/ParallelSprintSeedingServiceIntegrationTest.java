package kz.vainshtein.raftscore.bracket;

import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ParallelSprintSeedingServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired ParallelSprintSeedingService service;
    @Autowired ParallelSprintResultService resultService;
    @Autowired kz.vainshtein.raftscore.entry.services.CrewEligibilityService eligibility;
    @Autowired kz.vainshtein.raftscore.scoring.RecalculationService recalculation;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired ParticipantRepository participants;
    @Autowired CrewMembershipRepository memberships;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired org.springframework.web.context.WebApplicationContext context;

    @Test
    void emptySemifinalBeforeOtherResultResolvesBothFinals() { verifyEmptySemifinal(false); }

    @Test
    void emptySemifinalAfterOtherResultResolvesBothFinals() { verifyEmptySemifinal(true); }

    private void verifyEmptySemifinal(boolean playOtherFirst) {
        var competition = competitions.save(Competition.builder().title("Empty semifinal").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var table = new PlacementPointsTable(competition, "Points", TiePointsPolicy.SHARED_PLACE_SAME_POINTS,
                List.of(new PlacementPointsRow(1, 100), new PlacementPointsRow(3, 60)));
        entityManager.persist(table);
        parallel.setPointsTable(table);
        for (int n = 1; n <= 4; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var all = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId());
        var empty = all.get(0);
        var other = all.get(1);
        var withdrawn1 = empty.getCrew1().getId();
        var withdrawn2 = empty.getCrew2().getId();
        var winnerId = other.getCrew1().getId();
        var loserId = other.getCrew2().getId();
        var finish = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED;
        if (playOtherFirst) resultService.record(other.getId(), other.getVersion(), new ParallelSprintResultService.Result(finish, 100), new ParallelSprintResultService.Result(finish, 120));
        eligibility.withdrawFromUnplayedBrackets(withdrawn1);
        eligibility.withdrawFromUnplayedBrackets(withdrawn2);
        entityManager.flush(); entityManager.clear();
        other = heats.findById(other.getId()).orElseThrow();
        if (!playOtherFirst) resultService.record(other.getId(), other.getVersion(), new ParallelSprintResultService.Result(finish, 100), new ParallelSprintResultService.Result(finish, 120));
        entityManager.flush(); entityManager.clear();
        var finals = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).stream().filter(h -> h.getRoundNumber() == 2).toList();
        assertThat(finals).hasSize(2);
        assertThat(finals).allSatisfy(h -> { assertThat(h.isSlot1Resolved()).isTrue(); assertThat(h.isSlot2Resolved()).isTrue(); assertThat(h.winner()).isNotNull(); });
        assertThat(finals.get(0).winner().getId()).isEqualTo(winnerId);
        assertThat(finals.get(1).winner().getId()).isEqualTo(loserId);
        var scored = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(parallel.getId(), category.getId());
        assertThat(scored).anySatisfy(r -> { assertThat(r.getCrewEntry().getId()).isEqualTo(winnerId); assertThat(r.getPlace()).isEqualTo(1); assertThat(r.getPoints()).isEqualTo(100); });
        assertThat(scored).anySatisfy(r -> { assertThat(r.getCrewEntry().getId()).isEqualTo(loserId); assertThat(r.getPlace()).isEqualTo(3); assertThat(r.getPoints()).isEqualTo(60); });
        resultService.annul(other.getId(), heats.findById(other.getId()).orElseThrow().getVersion(), "Correction");
        entityManager.flush(); entityManager.clear();
        var reopened = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).stream().filter(h -> h.getRoundNumber() == 2).toList();
        assertThat(reopened).allSatisfy(h -> { assertThat(h.isSlot1Resolved()).isTrue(); assertThat(h.isSlot2Resolved()).isFalse(); assertThat(h.winner()).isNull(); });
    }

    @Test
    void withdrawalFromUnplayedFirstRoundAdvancesTheOpponentAsBye() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 4; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var semi = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).getFirst();
        var withdrawing = semi.getCrew1();
        var opponent = semi.getCrew2();
        eligibility.withdrawFromUnplayedBrackets(withdrawing.getId());
        assertThat(semi.getCrew1()).isNull();
        assertThat(semi.getWinnerSlot()).isEqualTo(2);
        var destination = semi.getNextHeat();
        assertThat(semi.getNextSlot() == 1 ? destination.getCrew1() : destination.getCrew2()).isEqualTo(opponent);
    }

    @Test
    void seedsTiesAndByesWithoutAdvancingUnresolvedHeats() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var other = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Other").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n : List.of(5, 4, 3, 2, 1)) {
            var crew = crew(category, n);
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew).place(n <= 2 ? 1 : n).status(CalculatedResultStatus.FINISHED).build());
        }
        var excluded = crew(category, 6);
        results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(excluded).status(CalculatedResultStatus.DNF).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        entityManager.flush();
        entityManager.clear();
        var generated = heats.findAll().stream().filter(h -> h.getBracket().getId().equals(bracket.getId())).toList();
        assertThat(generated).hasSize(8);
        var first = generated.stream().filter(h -> h.getRoundNumber() == 1).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(first).extracting(h -> h.getCrew1().getStartNumber()).containsExactly(4, 2, 3, 1);
        assertThat(first).extracting(h -> h.getCrew2() == null ? null : h.getCrew2().getStartNumber()).containsExactly(5, null, null, null);
        var second = generated.stream().filter(h -> h.getRoundNumber() == 2).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(second).extracting(h -> h.getCrew1() == null ? null : h.getCrew1().getStartNumber()).containsExactly(null, 3);
        assertThat(second).extracting(h -> h.getCrew2().getStartNumber()).containsExactly(2, 1);
        assertThat(generated.stream().filter(h -> h.getRoundNumber() == 3).findFirst().orElseThrow().getCrew1()).isNull();
        assertThat(first).allSatisfy(h -> assertThat(h.getNextHeat()).isNotNull());
        assertThatThrownBy(() -> service.seed(parallel.getId(), other.getId(), sprint.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.seed(parallel.getId(), category.getId(), sprint.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void upgradesUnfinishedLegacyBracketAndPreservesRecordedHeats() throws Exception {
        var competition = competitions.save(Competition.builder().title("Legacy").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var bracket = entityManager.merge(ParallelSprintBracket.builder().discipline(parallel).category(category).build());
        var a = crew(category, 1); var b = crew(category, 2); var c = crew(category, 3); var d = crew(category, 4);
        var fin = heats.save(ParallelSprintHeat.builder().bracket(bracket).roundNumber(2).heatNumber(1).crew1(a).build());
        var finished = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED;
        var semi1 = heats.save(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(a).crew2(c)
                .status1(finished).status2(finished).time1Centiseconds(100).time2Centiseconds(300).winnerSlot(1).nextHeat(fin).nextSlot(1).build());
        var semi2 = heats.save(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(2).crew1(b).crew2(d).nextHeat(fin).nextSlot(2).build());
        entityManager.flush();
        recalculation.recalculateLocked(parallel, category.getId());
        assertThat(results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(parallel.getId(), category.getId()))
                .anySatisfy(r -> { assertThat(r.getCrewEntry().getId()).isEqualTo(c.getId()); assertThat(r.getPlace()).isEqualTo(3); });
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var path = "/api/competitions/" + competition.getId() + "/disciplines/" + parallel.getId() + "/categories/" + category.getId() + "/bracket/bronze-final";
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("FULL_ACCESS");
        var reader = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY");
        var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(reader).with(csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(admin).with(csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var all = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId());
        var bronze = all.stream().filter(h -> h.getRoundNumber() == 2 && h.getHeatNumber() == 2).findFirst().orElseThrow();
        assertThat(bronze.getCrew1().getId()).isEqualTo(c.getId());
        assertThat(bronze.getCrew2()).isNull();
        assertThat(semi1.getWinnerSlot()).isEqualTo(1);
        assertThat(semi1.getTime2Centiseconds()).isEqualTo(300);
        assertThat(results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(parallel.getId(), category.getId())).isEmpty();
        assertThat(fin.getCrew1().getId()).isEqualTo(a.getId());
        resultService.record(semi2.getId(), semi2.getVersion(), new ParallelSprintResultService.Result(finished, 100), new ParallelSprintResultService.Result(finished, 200));
        assertThat(bronze.getCrew2().getId()).isEqualTo(d.getId());
        assertThatThrownBy(() -> service.addBronzeFinal(parallel.getId(), category.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToUpgradeCompletedLegacyBracket() {
        var competition = competitions.save(Competition.builder().title("Legacy").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var bracket = entityManager.merge(ParallelSprintBracket.builder().discipline(parallel).category(category).build());
        var a = crew(category, 1); var b = crew(category, 2);
        heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(2).heatNumber(1).crew1(a).crew2(b).winnerSlot(1).build());
        assertThatThrownBy(() -> service.addBronzeFinal(parallel.getId(), category.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("completed bracket");
        assertThat(heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId())).hasSize(1);
    }

    @Test
    void fullEightCrewBracketFollowsAppendixFour() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 8; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var pairs = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).stream()
                .filter(h -> h.getRoundNumber() == 1)
                .map(h -> List.of(h.getCrew1().getStartNumber(), h.getCrew2().getStartNumber())).toList();
        assertThat(pairs).containsExactly(List.of(8, 4), List.of(6, 2), List.of(7, 3), List.of(5, 1));
    }

    @Test
    void bronzeFinalAdvancesLosersAndDeterminesPoints() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var table = new PlacementPointsTable(competition, "Points", TiePointsPolicy.SHARED_PLACE_SAME_POINTS,
                List.of(new PlacementPointsRow(3, 180), new PlacementPointsRow(4, 170)));
        entityManager.persist(table);
        parallel.setPointsTable(table);
        for (int n = 1; n <= 4; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var all = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId());
        var semi1 = all.get(0); var semi2 = all.get(1);
        var finalB = all.stream().filter(h -> h.getRoundNumber() == 2 && h.getHeatNumber() == 2).findFirst().orElseThrow();
        var finished = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED;
        resultService.record(semi1.getId(), semi1.getVersion(), new ParallelSprintResultService.Result(finished, 100), new ParallelSprintResultService.Result(finished, 300));
        resultService.record(semi2.getId(), semi2.getVersion(), new ParallelSprintResultService.Result(finished, 100), new ParallelSprintResultService.Result(finished, 200));
        assertThat(finalB.getCrew1().getId()).isEqualTo(semi1.getCrew2().getId());
        assertThat(finalB.getCrew2().getId()).isEqualTo(semi2.getCrew2().getId());
        resultService.record(finalB.getId(), finalB.getVersion(), new ParallelSprintResultService.Result(finished, 100), new ParallelSprintResultService.Result(finished, 200));
        var scored = results.findByDisciplineIdAndCrewEntryCompetitionCategoryId(parallel.getId(), category.getId());
        assertThat(scored).anySatisfy(r -> { assertThat(r.getCrewEntry().getId()).isEqualTo(semi1.getCrew2().getId()); assertThat(r.getPlace()).isEqualTo(3); assertThat(r.getPoints()).isEqualTo(180); });
        assertThat(scored).anySatisfy(r -> { assertThat(r.getCrewEntry().getId()).isEqualTo(semi2.getCrew2().getId()); assertThat(r.getPlace()).isEqualTo(4); assertThat(r.getPoints()).isEqualTo(170); });
        assertThatThrownBy(() -> resultService.annul(semi1.getId(), semi1.getVersion(), "Correction"))
                .isInstanceOf(ParallelSprintResultService.DependentHeats.class)
                .satisfies(ex -> assertThat(((ParallelSprintResultService.DependentHeats) ex).ids).contains(finalB.getId()));
        resultService.annul(finalB.getId(), finalB.getVersion(), "Correction");
        resultService.annul(semi1.getId(), semi1.getVersion(), "Correction");
        assertThat(semi1.getStatus1()).isNull();
        assertThat(semi2.getStatus1()).isEqualTo(finished);
        assertThat(finalB.getCrew1()).isNull();
        var savedCrewIds = jdbc.queryForList("SELECT crew_id FROM result_annulments WHERE discipline_id = ? AND reason = ?", Long.class, parallel.getId(), "Correction");
        assertThat(savedCrewIds).containsExactlyInAnyOrder(semi1.getCrew1().getId(), semi1.getCrew2().getId(), semi1.getCrew2().getId(), semi2.getCrew2().getId());
    }

    @Test
    void preliminaryPairsUseCorrespondingHalves() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 6; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var pairs = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).stream()
                .filter(h -> h.getRoundNumber() == 1 && h.getCrew2() != null)
                .map(h -> List.of(h.getCrew1().getStartNumber(), h.getCrew2().getStartNumber())).toList();
        assertThat(pairs).containsExactlyInAnyOrder(List.of(3, 5), List.of(4, 6));
    }

    @Test
    void recordsResultsAndAdvancesOnlyValidWinners() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 4; n++) {
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        }
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        entityManager.flush();
        entityManager.clear();
        var first = heats.findAll().stream().filter(h -> h.getBracket().getId().equals(bracket.getId()) && h.getRoundNumber() == 1).findFirst().orElseThrow();
        var finished = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED;
        var dnf = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.DNF;
        var fast = new ParallelSprintResultService.Result(finished, 100);
        var slow = new ParallelSprintResultService.Result(finished, 120);
        assertThatThrownBy(() -> new ParallelSprintResultService.Result(dnf, 20)).isInstanceOf(IllegalArgumentException.class);
        var heatId = first.getId();
        var version = first.getVersion();
        resultService.record(heatId, version, fast, fast);
        assertThat(first.getWinnerSlot()).isNull();
        assertThat(first.getStatus1()).isEqualTo(finished);
        var noFinish = new ParallelSprintResultService.Result(dnf, null);
        resultService.record(heatId, first.getVersion(), noFinish, noFinish);
        assertThat(first.getWinnerSlot()).isNull();
        assertThat(first.getStatus2()).isEqualTo(dnf);
        resultService.record(heatId, first.getVersion(), fast, fast, first.getCrew2().getId());
        assertThat(first.getWinnerSlot()).isEqualTo(2);
        assertThat(first.isWinnerOverridden()).isTrue();
        resultService.record(first.getId(), first.getVersion(), fast, slow);
        entityManager.flush();
        entityManager.clear();
        first = heats.findById(first.getId()).orElseThrow();
        assertThat(first.getWinnerSlot()).isEqualTo(1);
        assertThat(first.getTime2Centiseconds()).isEqualTo(120);
        var advanced = first.getNextSlot() == 1 ? first.getNextHeat().getCrew1() : first.getNextHeat().getCrew2();
        assertThat(advanced.getId()).isEqualTo(first.getCrew1().getId());
        var sibling = heats.findAll().stream().filter(h -> h.getBracket().getId().equals(bracket.getId()) && h.getRoundNumber() == 1 && !h.getId().equals(heatId)).findFirst().orElseThrow();
        resultService.record(sibling.getId(), sibling.getVersion(), fast, slow);
        var finalHeat = first.getNextHeat();
        resultService.record(finalHeat.getId(), finalHeat.getVersion(), fast, slow);
        resultService.record(heatId, first.getVersion(), new ParallelSprintResultService.Result(finished, 90), slow);
        assertThat(first.getTime1Centiseconds()).isEqualTo(90);
        var correctedVersion = first.getVersion();
        assertThatThrownBy(() -> resultService.record(heatId, correctedVersion, slow, fast))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already been played");
    }
    @Test
    void rejectedWinnerChangeLeavesCommittedResultsAndDestinationUnchanged() {
        var competition = competitions.save(Competition.builder().title("Rollback race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var sprint = discipline(competition, category, DisciplineType.SPRINT);
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        for (int n = 1; n <= 4; n++)
            results.save(CalculatedDisciplineResult.builder().discipline(sprint).crewEntry(crew(category, n)).place(n).status(CalculatedResultStatus.FINISHED).build());
        var bracket = service.seed(parallel.getId(), category.getId(), null);
        var firstRound = heats.findAll().stream().filter(h -> h.getBracket().getId().equals(bracket.getId()) && h.getRoundNumber() == 1).toList();
        var firstId = firstRound.get(0).getId();
        var secondId = firstRound.get(1).getId();
        var finalId = firstRound.get(0).getNextHeat().getId();
        var bracketId = bracket.getId();
        var categoryId = category.getId();
        var competitionId = competition.getId();
        var sprintId = sprint.getId();
        var parallelId = parallel.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            var finished = kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED;
            var fast = new ParallelSprintResultService.Result(finished, 100);
            var slow = new ParallelSprintResultService.Result(finished, 120);
            resultService.record(firstId, 0, fast, slow);
            var replayVersion = heats.findById(firstId).orElseThrow().getVersion();
            resultService.record(firstId, 0, fast, slow);
            assertThat(heats.findById(firstId).orElseThrow().getVersion()).isEqualTo(replayVersion);
            assertThatThrownBy(() -> resultService.record(firstId, 0, slow, fast)).isInstanceOf(IllegalStateException.class);
            var winnerId = transactionTemplate.execute(status -> heats.findById(firstId).orElseThrow().getCrew2().getId());
            resultService.record(firstId, replayVersion, fast, slow, winnerId);
            resultService.record(firstId, replayVersion, fast, slow, winnerId);
            assertThatThrownBy(() -> resultService.record(firstId, replayVersion, fast, slow)).isInstanceOf(IllegalStateException.class);
            resultService.record(secondId, 0, fast, slow);
            var finalVersion = heats.findById(finalId).orElseThrow().getVersion();
            resultService.record(finalId, finalVersion, fast, slow);
            var version = heats.findById(firstId).orElseThrow().getVersion();
            assertThatThrownBy(() -> resultService.record(firstId, version, fast, slow)).isInstanceOf(IllegalStateException.class);
            TestTransaction.start();
            var persisted = heats.findById(firstId).orElseThrow();
            assertThat(persisted.getWinnerSlot()).isEqualTo(2);
            assertThat(persisted.getTime1Centiseconds()).isEqualTo(100);
            var destination = heats.findById(finalId).orElseThrow();
            var advanced = persisted.getNextSlot() == 1 ? destination.getCrew1() : destination.getCrew2();
            assertThat(advanced.getId()).isEqualTo(persisted.getCrew2().getId());
        } finally {
            if (TestTransaction.isActive()) TestTransaction.end();
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.update("delete from parallel_sprint_heats where bracket_id = ?", bracketId);
                jdbc.update("delete from parallel_sprint_brackets where id = ?", bracketId);
                jdbc.update("delete from calculated_discipline_results where discipline_id in (?, ?)", sprintId, parallelId);
                jdbc.update("delete from crew_memberships where competition_category_id = ?", categoryId);
                jdbc.update("delete from crew_entries where competition_category_id = ?", categoryId);
                jdbc.update("delete from discipline_categories where discipline_id in (?, ?)", sprintId, parallelId);
                jdbc.update("delete from disciplines where id in (?, ?)", sprintId, parallelId);
                jdbc.update("delete from competition_categories where id = ?", categoryId);
                jdbc.update("delete from competitions where id = ?", competitionId);
            });
        }
    }
    @Test
    void resultApiEnforcesScopeConfirmationAndAuthorization() throws Exception {
        var competition = competitions.save(Competition.builder().title("API race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        competitions.flush();
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var bracket = kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracket.builder().discipline(parallel).category(category).build();
        entityManager.persist(bracket);
        var first = crew(category, 1);
        var second = crew(category, 2);
        var heat = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(first).crew2(second).build());
        var half = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(2).crew1(first).build());
        jdbc.update("update parallel_sprint_heats set version = 1 where id = ?", half.getId());
        entityManager.clear();
        var tied = new ParallelSprintResultService.Result(kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED, 100);
        assertThatThrownBy(() -> resultService.record(half.getId(), 0, tied, tied, first.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("heat has changed");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var path = "/api/competitions/" + competition.getId() + "/disciplines/" + parallel.getId() + "/categories/" + category.getId() + "/brackets/" + bracket.getId();
        var body = "{\"version\":0,\"first\":{\"status\":\"FINISHED\",\"timeCentiseconds\":100},\"second\":{\"status\":\"FINISHED\",\"timeCentiseconds\":100},\"winnerCrewId\":" + second.getId() + ",\"confirmed\":true}";
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("FULL_ACCESS");
        var reader = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY");
        var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(reader).with(csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body.replace("\"version\":0", "\"version\":0.5"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body.replace("true", "false"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body.replace("\"winnerCrewId\":" + second.getId(), "\"winnerCrewId\":999999"))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path.replace("/competitions/" + competition.getId(), "/competitions/999999") ).with(reader)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(reader)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Person"))));
    }
    private Discipline discipline(Competition competition, CompetitionCategory category, DisciplineType type) {
        var d = disciplines.save(Discipline.builder().competition(competition).name(type.name()).type(type).build());
        d.transitionTo(DisciplineStatus.STARTED);
        d.replaceCategories(List.of(category));
        disciplines.flush();
        return d;
    }
    private CrewEntry crew(CompetitionCategory category, int n) {
        var crew = crews.save(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).regionOrClub("Club").representative("Person").startNumber(n).build());
        for (int i = 1; i <= category.getCrewSize(); i++) {
            var person = participants.save(Participant.builder().surname("Crew" + n).givenName("Person" + i).build());
            memberships.save(CrewMembership.builder().crewEntry(crew).participant(person).position(i).build());
        }
        memberships.flush();
        return crew;
    }
}
