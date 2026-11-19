package kz.vainshtein.raftscore.protocol;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.bracket.*;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import kz.vainshtein.raftscore.scoring.RecalculationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class MissingAttemptIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired ParticipantRepository participants;
    @Autowired CrewMembershipRepository memberships;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired SlalomConfigurationRepository slalom;
    @Autowired WebApplicationContext context;
    @Autowired AttemptResultRepository attempts;
    @Autowired RecalculationService recalculation;
    @Autowired ParallelSprintSeedingService seeding;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired ParallelSprintResultService heatResults;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    record PlayedBracket(long competitionId, long categoryId, long sprintId, long parallelId, long crewId, long bracketId, long version) { }

    @Test
    void repairsOnlyMissingEligibleAttemptBeforeFirstIssueAndAllowsRetry() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Legacy race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category)); discipline.transitionTo(DisciplineStatus.STARTED);
        slalom.saveAndFlush(new SlalomConfiguration(discipline, 1, 1));
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew").startNumber(1).build());
        var participant = participants.saveAndFlush(Participant.builder().surname("Racer").givenName("One").build());
        memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
        var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        slots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(1).build());
        competition.transitionTo(CompetitionStatus.COMPLETED); competitions.flush(); disciplines.flush();
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var admin = user("secretary").roles("FULL_ACCESS"); var reader = user("reader").roles("READ_ONLY");
        var base = "/api/competitions/" + competition.getId();
        var attempt = base + "/disciplines/" + discipline.getId() + "/categories/" + category.getId() + "/crews/" + crew.getId() + "/attempts/corrections/";
        var body = "{\"reason\":\"Missing result\",\"result\":{\"version\":null,\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":0}]}}";
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isConflict());
        mvc.perform(put(attempt + "2").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(put(attempt + "1").with(reader).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(attempt + "1").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(put(attempt + "1").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.edition").value(1)).andExpect(jsonPath("$.changes.length()").value(1)).andExpect(jsonPath("$.changes[0].detail.before.status").value("MISSING"));
    }

    @Test
    void repairsSeveralAvailableHeatsBeforeIssuing() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Legacy bracket").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
        var sprint = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        sprint.replaceCategories(List.of(category)); sprint.transitionTo(DisciplineStatus.STARTED);
        var parallel = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Parallel").type(DisciplineType.PARALLEL_SPRINT).build());
        parallel.replaceCategories(List.of(category)); parallel.transitionTo(DisciplineStatus.STARTED); parallel.setQualificationDiscipline(sprint);
        var list = lists.saveAndFlush(StartList.builder().discipline(sprint).category(category).build());
        for (int n = 1; n <= 4; n++) {
            var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).startNumber(n).build());
            var participant = participants.saveAndFlush(Participant.builder().surname("Racer").givenName("" + n).build());
            memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
            slots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(n).build());
            attempts.saveAndFlush(AttemptResult.builder().discipline(sprint).crewEntry(crew).attemptNumber(1)
                    .durationCentiseconds(100 + n).penaltyCentiseconds(0).status(AttemptResultStatus.FINISHED).build());
        }
        recalculation.recalculateLocked(sprint, category.getId());
        var bracket = seeding.seed(parallel.getId(), category.getId(), sprint.getId());
        var one = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 100);
        var two = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 110);
        for (var heat : heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()))
            if (heat.getRoundNumber() == 1) heatResults.record(heat.getId(), heat.getVersion(), one, two);
        competition.transitionTo(CompetitionStatus.COMPLETED); competitions.flush(); disciplines.flush();
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var admin = user("secretary").roles("FULL_ACCESS");
        var base = "/api/competitions/" + competition.getId();
        var prefix = base + "/disciplines/" + parallel.getId() + "/categories/" + category.getId() + "/brackets/" + bracket.getId() + "/heats/";
        var finals = heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()).stream().filter(h -> h.getRoundNumber() == 2).toList();
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isConflict());
        for (int i = 0; i < finals.size(); i++) {
            var heat = finals.get(i);
            var body = "{\"reason\":\"Missing heat\",\"result\":{\"version\":" + heat.getVersion() + ",\"first\":{\"status\":\"FINISHED\",\"timeCentiseconds\":100},\"second\":{\"status\":\"FINISHED\",\"timeCentiseconds\":110}}}";
            mvc.perform(put(prefix + heat.getId() + "/correction").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
            if (i == 0) mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isConflict());
        }
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2)).andExpect(jsonPath("$.changes[0].detail.before.status1").doesNotExist());
    }

    @Test
    void issuedCorrectionContainsPersistedNormalizedTimingAndGates() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Timed race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category)); discipline.transitionTo(DisciplineStatus.STARTED);
        slalom.saveAndFlush(new SlalomConfiguration(discipline, 1, 1));
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Timing crew").startNumber(1).build());
        var participant = participants.saveAndFlush(Participant.builder().surname("Racer").givenName("One").build());
        memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
        var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        slots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(1).build());
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var admin = user("secretary").roles("FULL_ACCESS");
        var base = "/api/competitions/" + competition.getId();
        var attempt = base + "/disciplines/" + discipline.getId() + "/categories/" + category.getId() + "/crews/" + crew.getId() + "/attempts/";
        var original = "{\"startInstant\":\"2026-01-01T12:00:00.123456789Z\",\"finishInstant\":\"2026-01-01T12:00:02.133456789Z\",\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":5}]}";
        mvc.perform(put(attempt + "1").with(admin).with(csrf()).contentType("application/json").content(original)).andExpect(status().isOk());
        competition.transitionTo(CompetitionStatus.COMPLETED); competitions.flush(); disciplines.flush();
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isOk());
        var saved = attempts.findByDisciplineIdAndCrewEntryIdAndAttemptNumber(discipline.getId(), crew.getId(), 1).orElseThrow();
        var body = "{\"reason\":\"Time corrected\",\"result\":{\"version\":" + saved.getVersion() + ",\"startInstant\":\"2026-01-01T13:00:00.987654321Z\",\"finishInstant\":\"2026-01-01T13:00:02.997654321Z\",\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":50}]}}";
        mvc.perform(put(attempt + "corrections/1").with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].detail.before.durationCentiseconds").value(201))
                .andExpect(jsonPath("$.changes[0].detail.before.startInstant").value("2026-01-01T12:00:00.123456Z"))
                .andExpect(jsonPath("$.changes[0].detail.before.finishInstant").value("2026-01-01T12:00:02.133456Z"))
                .andExpect(jsonPath("$.changes[0].detail.before.gates[0].penaltySeconds").value(5))
                .andExpect(jsonPath("$.changes[0].detail.after.durationCentiseconds").value(201))
                .andExpect(jsonPath("$.changes[0].detail.after.startInstant").value("2026-01-01T13:00:00.987654Z"))
                .andExpect(jsonPath("$.changes[0].detail.after.finishInstant").value("2026-01-01T13:00:02.997654Z"))
                .andExpect(jsonPath("$.changes[0].detail.after.gates[0].penaltySeconds").value(50));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void seedingChangeRollsBackButOrderPreservingCorrectionSucceeds() throws Exception {
        var fixture = transactions.execute(tx -> {
            var competition = competitions.saveAndFlush(Competition.builder().title("Played bracket").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
            competition.transitionTo(CompetitionStatus.STARTED);
            var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
            var sprint = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
            sprint.replaceCategories(List.of(category)); sprint.transitionTo(DisciplineStatus.STARTED);
            var parallel = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Parallel").type(DisciplineType.PARALLEL_SPRINT).build());
            parallel.replaceCategories(List.of(category)); parallel.transitionTo(DisciplineStatus.STARTED); parallel.setQualificationDiscipline(sprint);
            var list = lists.saveAndFlush(StartList.builder().discipline(sprint).category(category).build());
            CrewEntry first = null;
            for (int n = 1; n <= 4; n++) {
                var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew " + n).startNumber(n).build());
                if (n == 1) first = crew;
                var participant = participants.saveAndFlush(Participant.builder().surname("Racer").givenName("" + n).build());
                memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
                slots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(n).build());
                attempts.saveAndFlush(AttemptResult.builder().discipline(sprint).crewEntry(crew).attemptNumber(1)
                        .durationCentiseconds(100 * n).penaltyCentiseconds(0).status(AttemptResultStatus.FINISHED).build());
            }
            recalculation.recalculateLocked(sprint, category.getId());
            var bracket = seeding.seed(parallel.getId(), category.getId(), sprint.getId());
            var one = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 100);
            var two = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 110);
            for (int round = 1; round <= 2; round++) for (var heat : heats.findByBracketIdOrderByRoundNumberAscHeatNumberAsc(bracket.getId()))
                if (heat.getRoundNumber() == round) heatResults.record(heat.getId(), heat.getVersion(), one, two);
            competition.transitionTo(CompetitionStatus.COMPLETED); competitions.flush(); disciplines.flush();
            var attempt = attempts.findByDisciplineIdAndCrewEntryIdAndAttemptNumber(sprint.getId(), first.getId(), 1).orElseThrow();
            return new PlayedBracket(competition.getId(), category.getId(), sprint.getId(), parallel.getId(), first.getId(), bracket.getId(), attempt.getVersion());
        });
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var admin = user("secretary").roles("FULL_ACCESS");
        var base = "/api/competitions/" + fixture.competitionId();
        var path = base + "/disciplines/" + fixture.sprintId() + "/categories/" + fixture.categoryId() + "/crews/" + fixture.crewId() + "/attempts/corrections/1";
        var grid = base + "/disciplines/" + fixture.sprintId() + "/categories/" + fixture.categoryId() + "/attempt-grid";
        var bracket = base + "/disciplines/" + fixture.parallelId() + "/categories/" + fixture.categoryId() + "/bracket";
        mvc.perform(post(base + "/protocols").with(admin).with(csrf())).andExpect(status().isOk());
        var beforeBracket = mvc.perform(get(bracket).with(admin)).andReturn().getResponse().getContentAsString();
        var beforeGrid = mvc.perform(get(grid).with(admin)).andReturn().getResponse().getContentAsString();
        var beforeProtocol = mvc.perform(get(base + "/protocols").with(admin)).andReturn().getResponse().getContentAsString();
        var body = "{\"reason\":\"Timing\",\"result\":{\"version\":" + fixture.version() + ",\"durationCentiseconds\":500,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[]}}";
        mvc.perform(put(path).with(admin).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("protocol.seeding_change"));
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get(grid).with(admin)).andReturn().getResponse().getContentAsString()).isEqualTo(beforeGrid);
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get(bracket).with(admin)).andReturn().getResponse().getContentAsString()).isEqualTo(beforeBracket);
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get(base + "/protocols").with(admin)).andReturn().getResponse().getContentAsString()).isEqualTo(beforeProtocol);
        mvc.perform(put(path).with(admin).with(csrf()).contentType("application/json").content(body.replace("500", "90"))).andExpect(status().isOk());
        mvc.perform(get(base + "/protocols").with(admin)).andExpect(jsonPath("$[0].stale").value(true));
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get(bracket).with(admin)).andReturn().getResponse().getContentAsString()).isEqualTo(beforeBracket);
    }
}
