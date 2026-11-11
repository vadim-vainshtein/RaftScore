package kz.vainshtein.raftscore.startlist;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class StartListServiceIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired StartListService service;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired ParticipantRepository participants;
    @Autowired CrewMembershipRepository memberships;
    @Autowired kz.vainshtein.raftscore.timing.entities.AttemptResultRepository attempts;
    @Autowired kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracketRepository brackets;
    @Autowired kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeatRepository heats;
    @Autowired kz.vainshtein.raftscore.bracket.ParallelSprintResultService heatResults;
    @Autowired kz.vainshtein.raftscore.slalom.entities.SlalomConfigurationRepository configurations;
    @Autowired kz.vainshtein.raftscore.slalom.entities.SlalomGatePenaltyRepository penalties;
    @Autowired kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResultRepository calculated;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void generatesAndReplacesExactEligibleOrder() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.save(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category));
        disciplines.flush();
        var a = crew(category, 1, true);
        var b = crew(category, 2, true);
        crew(category, 3, false);
        var random = service.replace(competition.getId(), discipline.getId(), category.getId(), null, true, null, null);
        assertThat(random.slots()).extracting(StartListService.Slot::crewId).containsExactlyInAnyOrder(a.getId(), b.getId());
        var first = Instant.parse("2026-01-01T10:00:00Z");
        var manual = service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(b.getId(), a.getId()), false, first, 60L);
        assertThat(manual.slots()).extracting(StartListService.Slot::crewId).containsExactly(b.getId(), a.getId());
        assertThat(manual.slots()).extracting(StartListService.Slot::plannedTime).containsExactly(first, first.plusSeconds(60));
        assertThat(manual.slots()).extracting(StartListService.Slot::startNumber).containsExactly(2, 1);
        assertThatThrownBy(() -> service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(a.getId(), a.getId()), false, null, null)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.read(competition.getId(), discipline.getId(), category.getId()).slots()).extracting(StartListService.Slot::crewId).containsExactly(b.getId(), a.getId());
        assertThatThrownBy(() -> service.read(-1, discipline.getId(), category.getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resultsBlockReplacementUntilExplicitRemoval() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(1).build());
        var other = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Other").scoringGroupLabel("Other").crewSize(1).build());
        var discipline = disciplines.save(Discipline.builder().competition(competition).name("Sprint").type(DisciplineType.SPRINT).build());
        discipline.replaceCategories(List.of(category, other));
        disciplines.flush();
        var a = crew(category, 1, true);
        var b = crew(category, 2, true);
        var first = Instant.parse("2026-01-01T10:00:00Z");
        var original = service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(a.getId(), b.getId()), false, first, 60L);
        for (var status : kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.values()) {
            attempts.saveAndFlush(kz.vainshtein.raftscore.timing.entities.AttemptResult.builder().discipline(discipline).crewEntry(a).attemptNumber(1)
                    .status(status).durationCentiseconds(status == kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED ? 100 : null).build());
            assertThatThrownBy(() -> service.replace(competition.getId(), discipline.getId(), category.getId(), null, true, null, null)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(b.getId(), a.getId()), false, null, null)).isInstanceOf(ResponseStatusException.class);
            assertThat(service.read(competition.getId(), discipline.getId(), category.getId())).isEqualTo(original);
            service.replace(competition.getId(), discipline.getId(), other.getId(), List.of(), false, null, null);
            service.clearResults(competition.getId(), discipline.getId(), category.getId());
        }
        assertThat(service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(b.getId(), a.getId()), false, null, null).slots())
                .extracting(StartListService.Slot::crewId).containsExactly(b.getId(), a.getId());
    }
    @Test
    void playedHeatBlocksReplacementAndClearingRemovesBracket() {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(1).build());
        var discipline = disciplines.save(Discipline.builder().competition(competition).name("Parallel").type(DisciplineType.PARALLEL_SPRINT).build());
        discipline.replaceCategories(List.of(category));
        disciplines.flush();
        var a = crew(category, 1, true);
        var b = crew(category, 2, true);
        service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(a.getId(), b.getId()), false, null, null);
        var bracket = brackets.saveAndFlush(kz.vainshtein.raftscore.bracket.entities.ParallelSprintBracket.builder().discipline(discipline).category(category).build());
        var heat = heats.saveAndFlush(kz.vainshtein.raftscore.bracket.entities.ParallelSprintHeat.builder().bracket(bracket).roundNumber(1).heatNumber(1).crew1(a).crew2(b).build());
        var finished = new kz.vainshtein.raftscore.bracket.ParallelSprintResultService.Result(kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.FINISHED, 100);
        var dns = new kz.vainshtein.raftscore.bracket.ParallelSprintResultService.Result(kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.DNS, null);
        heatResults.record(heat.getId(), heat.getVersion(), finished, dns);
        assertThatThrownBy(() -> service.replace(competition.getId(), discipline.getId(), category.getId(), null, true, null, null)).isInstanceOf(ResponseStatusException.class);
        service.clearResults(competition.getId(), discipline.getId(), category.getId());
        assertThat(service.replace(competition.getId(), discipline.getId(), category.getId(), List.of(b.getId(), a.getId()), false, null, null).slots())
                .extracting(StartListService.Slot::crewId).containsExactly(b.getId(), a.getId());
    }
    @Test
    void clearEndpointDeletesSlalomDependenciesOnlyForSelectedCategory() throws Exception {
        var competition = competitions.save(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        var category = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Open").scoringGroupLabel("Open").crewSize(1).build());
        var other = categories.save(CompetitionCategory.builder().competition(competition).classLabel("Other").scoringGroupLabel("Other").crewSize(1).build());
        var discipline = disciplines.save(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category, other));
        disciplines.flush();
        var config = configurations.saveAndFlush(new kz.vainshtein.raftscore.slalom.entities.SlalomConfiguration(discipline, 1));
        var selectedCrew = crew(category, 1, true);
        var otherCrew = crew(other, 2, true);
        var selectedAttempt = attempts.saveAndFlush(kz.vainshtein.raftscore.timing.entities.AttemptResult.builder().discipline(discipline).crewEntry(selectedCrew).attemptNumber(1).status(kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.DNS).build());
        var otherAttempt = attempts.saveAndFlush(kz.vainshtein.raftscore.timing.entities.AttemptResult.builder().discipline(discipline).crewEntry(otherCrew).attemptNumber(1).status(kz.vainshtein.raftscore.timing.entities.AttemptResultStatus.DNS).build());
        penalties.saveAndFlush(new kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty(selectedAttempt, config, 1, 5));
        penalties.saveAndFlush(new kz.vainshtein.raftscore.slalom.entities.SlalomGatePenalty(otherAttempt, config, 1, 5));
        for (var crew : List.of(selectedCrew, otherCrew))
            calculated.saveAndFlush(kz.vainshtein.raftscore.scoring.entities.CalculatedDisciplineResult.builder().discipline(discipline).crewEntry(crew).status(kz.vainshtein.raftscore.scoring.entities.CalculatedResultStatus.DNS).build());
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var url = "/api/competitions/" + competition.getId() + "/disciplines/" + discipline.getId() + "/categories/" + category.getId() + "/start-list/source-results";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("FULL_ACCESS"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT crew_entry_id FROM attempt_results WHERE discipline_id = ?", Long.class, discipline.getId())).isEqualTo(otherCrew.getId());
        assertThat(jdbc.queryForObject("SELECT attempt_result_id FROM slalom_gate_penalties WHERE configuration_id = ?", Long.class, config.getId())).isEqualTo(otherAttempt.getId());
        assertThat(jdbc.queryForObject("SELECT crew_entry_id FROM calculated_discipline_results WHERE discipline_id = ?", Long.class, discipline.getId())).isEqualTo(otherCrew.getId());
    }
    @Test
    void rejectsFractionalNumbersAndUnauthorizedWrites() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var url = "/api/competitions/1/disciplines/1/categories/1/start-list";
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("FULL_ACCESS");
        var csrf = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url + "/source-results").with(admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url + "/source-results")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY")).with(csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        for (var body : List.of("{\"random\":false,\"crewIds\":[1.5]}", "{\"random\":true,\"firstStart\":\"2026-01-01T00:00:00Z\",\"intervalSeconds\":1.5}")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).with(admin).with(csrf)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).with(admin)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"random\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY")).with(csrf)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"random\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
    private CrewEntry crew(CompetitionCategory category, int number, boolean eligible) {
        var crew = crews.save(CrewEntry.builder().competitionCategory(category).crewName("Crew " + number).regionOrClub("Club").representative("Person").startNumber(number).build());
        if (eligible) {
            var participant = participants.save(Participant.builder().surname("Person").givenName("Name").build());
            memberships.save(CrewMembership.builder().crewEntry(crew).participant(participant).position(1).build());
        }
        return crew;
    }
}
