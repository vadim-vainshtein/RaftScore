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
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired CalculatedDisciplineResultRepository results;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired org.springframework.web.context.WebApplicationContext context;

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
        assertThat(generated).hasSize(7);
        var first = generated.stream().filter(h -> h.getRoundNumber() == 1).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(first).extracting(h -> h.getCrew1().getStartNumber()).containsExactly(1, 4, 2, 3);
        assertThat(first).extracting(h -> h.getCrew2() == null ? null : h.getCrew2().getStartNumber()).containsExactly(null, 5, null, null);
        var second = generated.stream().filter(h -> h.getRoundNumber() == 2).sorted(java.util.Comparator.comparingInt(ParallelSprintHeat::getHeatNumber)).toList();
        assertThat(second).extracting(h -> h.getCrew1().getStartNumber()).containsExactly(1, 2);
        assertThat(second).extracting(h -> h.getCrew2() == null ? null : h.getCrew2().getStartNumber()).containsExactly(null, 3);
        assertThat(generated.stream().filter(h -> h.getRoundNumber() == 3).findFirst().orElseThrow().getCrew1()).isNull();
        assertThat(first).allSatisfy(h -> assertThat(h.getNextHeat()).isNotNull());
        assertThatThrownBy(() -> service.seed(parallel.getId(), other.getId(), sprint.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.seed(parallel.getId(), category.getId(), sprint.getId())).isInstanceOf(IllegalStateException.class);
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
            resultService.record(secondId, 0, fast, slow);
            var finalVersion = heats.findById(finalId).orElseThrow().getVersion();
            resultService.record(finalId, finalVersion, fast, slow);
            var version = heats.findById(firstId).orElseThrow().getVersion();
            assertThatThrownBy(() -> resultService.record(firstId, version, slow, fast)).isInstanceOf(IllegalStateException.class);
            TestTransaction.start();
            var persisted = heats.findById(firstId).orElseThrow();
            assertThat(persisted.getWinnerSlot()).isEqualTo(1);
            assertThat(persisted.getTime1Centiseconds()).isEqualTo(100);
            var destination = heats.findById(finalId).orElseThrow();
            var advanced = persisted.getNextSlot() == 1 ? destination.getCrew1() : destination.getCrew2();
            assertThat(advanced.getId()).isEqualTo(persisted.getCrew1().getId());
        } finally {
            if (TestTransaction.isActive()) TestTransaction.end();
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.update("delete from parallel_sprint_heats where bracket_id = ?", bracketId);
                jdbc.update("delete from parallel_sprint_brackets where id = ?", bracketId);
                jdbc.update("delete from calculated_discipline_results where discipline_id = ?", sprintId);
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
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(4).build());
        var parallel = discipline(competition, category, DisciplineType.PARALLEL_SPRINT);
        var bracket = kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracket.builder().discipline(parallel).category(category).build();
        entityManager.persist(bracket);
        var first = crew(category, 1);
        var second = crew(category, 2);
        var heat = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(first).crew2(second).build());
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
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path + "/heats/" + heat.getId() + "/result").with(admin).with(csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path.replace("/competitions/" + competition.getId(), "/competitions/999999") ).with(reader)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(reader)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Person"))));
    }
    private Discipline discipline(Competition competition, CompetitionCategory category, DisciplineType type) {
        var d = disciplines.save(Discipline.builder().competition(competition).name(type.name()).type(type).build());
        d.replaceCategories(List.of(category));
        disciplines.flush();
        return d;
    }
    private CrewEntry crew(CompetitionCategory category, int n) {
        return crews.save(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).regionOrClub("Club").representative("Person").startNumber(n).build());
    }
}
