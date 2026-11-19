package kz.vainshtein.raftscore.protocol;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.CompletionCheck;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.bracket.ParallelSprintResultService;
import kz.vainshtein.raftscore.bracket.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.timing.entities.AttemptResultStatus;
import kz.vainshtein.raftscore.timing.entities.AttemptResultRepository;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@SpringBootTest
class IssuedProtocolIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired ProtocolController controller;
    @Autowired ProtocolCorrections corrections;
    @Autowired DisciplineRepository disciplines;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired CrewEntryRepository crews;
    @Autowired ParticipantRepository participants;
    @Autowired CrewMembershipRepository memberships;
    @Autowired ParallelSprintBracketRepository brackets;
    @Autowired ParallelSprintHeatRepository heats;
    @Autowired ParallelSprintResultService heatResults;
    @Autowired StartListRepository startLists;
    @Autowired StartSlotRepository startSlots;
    @Autowired SlalomConfigurationRepository slalom;
    @Autowired AttemptResultRepository attempts;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean CompletionCheck completion;

    @Test @Transactional
    void retainsSnapshotsAndReissuesOnlyAfterCorrections() {
        when(completion.competition(anyLong())).thenReturn(List.of());
        var competition = competitions.saveAndFlush(Competition.builder().title("Original")
                .startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        competition.transitionTo(CompetitionStatus.COMPLETED);
        competitions.flush();
        var id = competition.getId();
        var first = controller.issue(id);
        assertThat(first.edition()).isEqualTo(1);
        assertThat(controller.issue(id).edition()).isEqualTo(1);
        corrections.record(id, "Penalty", "{\"before\":{},\"after\":{}}");
        corrections.record(id, "Second correction", "{\"before\":{},\"after\":{}}");
        assertThat(controller.history(id)).singleElement().satisfies(e -> assertThat(e.stale()).isTrue());
        var second = controller.issue(id);
        assertThat(second.edition()).isEqualTo(2);
        assertThat(second.changes()).hasSize(2);
        var history = controller.history(id);
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().stale()).isFalse();
        assertThat(history.getLast().stale()).isTrue();
        assertThat(history.getLast().snapshot()).isEqualTo(first.snapshot());
    }

    @ParameterizedTest
    @EnumSource(value = DisciplineStatus.class, names = {"STARTED", "COMPLETED"})
    @Transactional
    void correctingAFinalRecalculatesAndCreatesOneChangeEvenOnRetry(DisciplineStatus disciplineStatus) {
        when(completion.competition(anyLong())).thenReturn(List.of());
        var competition = competitions.saveAndFlush(Competition.builder().title("Race")
                .startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Parallel")
                .type(DisciplineType.PARALLEL_SPRINT).build());
        discipline.replaceCategories(List.of(category));
        discipline.transitionTo(DisciplineStatus.STARTED);
        var first = crew(category, "First", 1);
        var second = crew(category, "Second", 2);
        var bracket = brackets.saveAndFlush(ParallelSprintBracket.builder().discipline(discipline).category(category).build());
        var heat = heats.saveAndFlush(ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1)
                .crew1(first).crew2(second).status1(AttemptResultStatus.FINISHED).status2(AttemptResultStatus.FINISHED)
                .time1Centiseconds(100).time2Centiseconds(110).winnerSlot(1).build());
        if (disciplineStatus == DisciplineStatus.COMPLETED) discipline.transitionTo(DisciplineStatus.COMPLETED);
        competition.transitionTo(CompetitionStatus.COMPLETED);
        disciplines.flush(); competitions.flush();
        controller.issue(competition.getId());
        var version = heat.getVersion();
        var one = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 120);
        var two = new ParallelSprintResultService.Result(AttemptResultStatus.FINISHED, 110);
        heatResults.correct(heat.getId(), version, one, two, null, "Timing");
        heatResults.correct(heat.getId(), version, one, two, null, "Timing");
        assertThat(heat.getWinnerSlot()).isEqualTo(2);
        assertThat(controller.history(competition.getId()).getFirst().stale()).isTrue();
        var secondEdition = controller.issue(competition.getId());
        assertThat(secondEdition.changes()).hasSize(1);
        assertThat(secondEdition.changes().getFirst().detail().get("kind").asString()).isEqualTo("heat");
    }

    private CrewEntry crew(CompetitionCategory category, String name, int number) {
        var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName(name).startNumber(number).build());
        var participant = participants.saveAndFlush(Participant.builder().surname(name).givenName("Racer").build());
        memberships.saveAndFlush(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
        return crew;
    }

    @ParameterizedTest
    @EnumSource(value = DisciplineStatus.class, names = {"STARTED", "COMPLETED"})
    @Transactional
    void secretaryCanCorrectSlalomAfterIssueInBothClosedCompetitionStates(DisciplineStatus disciplineStatus) throws Exception {
        when(completion.competition(anyLong())).thenReturn(List.of());
        var competition = competitions.saveAndFlush(Competition.builder().title("Slalom")
                .startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        competition.transitionTo(CompetitionStatus.STARTED);
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R1").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom")
                .type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category)); discipline.transitionTo(DisciplineStatus.STARTED);
        slalom.saveAndFlush(new SlalomConfiguration(discipline, 1));
        var crew = crew(category, "Crew", 1);
        var list = startLists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        startSlots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(1).build());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var base = "/api/competitions/" + competition.getId() + "/disciplines/" + discipline.getId()
                + "/categories/" + category.getId() + "/crews/" + crew.getId() + "/attempts/";
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("secretary").roles("FULL_ACCESS");
        var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "1").with(admin).with(csrf)
                .contentType("application/json").content("{\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":50}]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        if (disciplineStatus == DisciplineStatus.COMPLETED) discipline.transitionTo(DisciplineStatus.COMPLETED);
        competition.transitionTo(CompetitionStatus.COMPLETED); disciplines.flush(); competitions.flush();
        controller.issue(competition.getId());
        var version = attempts.findByDisciplineIdAndCrewEntryIdAndAttemptNumber(discipline.getId(), crew.getId(), 1).orElseThrow().getVersion();
        var body = "{\"reason\":\"Gate correction\",\"result\":{\"version\":" + version + ",\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":0}]}}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "corrections/1").with(admin).with(csrf)
                .contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(controller.history(competition.getId()).getFirst().stale()).isTrue();
        assertThat(controller.issue(competition.getId()).changes()).hasSize(1);
    }
}
