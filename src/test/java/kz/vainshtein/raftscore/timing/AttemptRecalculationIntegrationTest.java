package kz.vainshtein.raftscore.timing;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.entry.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import kz.vainshtein.raftscore.timing.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AttemptRecalculationIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired CrewEntryRepository crews;
    @Autowired StartListRepository lists;
    @Autowired StartSlotRepository slots;
    @Autowired SlalomConfigurationRepository configurations;
    @Autowired AttemptResultRepository attempts;
    @Autowired SlalomGatePenaltyRepository penalties;
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    void gateOnlyEditAdvancesVersionAndStaleEditAndInvalidEditRollBack() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Slalom").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        try {
            var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
            var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
            discipline.replaceCategories(List.of(category));
            disciplines.saveAndFlush(discipline);
            configurations.saveAndFlush(new SlalomConfiguration(discipline, 1));
            var crew = crews.saveAndFlush(CrewEntry.builder().competitionCategory(category).crewName("Crew").regionOrClub("Club").representative("Coach").startNumber(1).build());
            var list = lists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
            slots.saveAndFlush(StartSlot.builder().startList(list).crew(crew).position(1).build());
            var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            var url = "/api/competitions/" + competition.getId() + "/disciplines/" + discipline.getId() + "/categories/" + category.getId() + "/crews/" + crew.getId() + "/attempts/1";
            var auth = user("admin").roles("FULL_ACCESS");
            var create = "{\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":0}]}";
            mvc.perform(put(url).with(auth).with(csrf()).contentType("application/json").content(create)).andExpect(status().isOk());
            var attempt = attempts.findByDisciplineIdAndCrewEntryIdAndAttemptNumber(discipline.getId(), crew.getId(), 1).orElseThrow();
            var version = attempt.getVersion();
            var update = "{\"version\":" + version + ",\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\",\"gates\":[{\"number\":1,\"penaltySeconds\":5}]}";
            mvc.perform(put(url).with(auth).with(csrf()).contentType("application/json").content(update)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(version + 1));
            mvc.perform(put(url).with(auth).with(csrf()).contentType("application/json").content(update)).andExpect(status().isConflict());
            var invalid = update.replace("\"version\":" + version, "\"version\":" + (version + 1)).replace("\"durationCentiseconds\":100", "\"durationCentiseconds\":90").replace("[{\"number\":1,\"penaltySeconds\":5}]", "[]");
            mvc.perform(put(url).with(auth).with(csrf()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            var unchanged = attempts.findById(attempt.getId()).orElseThrow();
            assertThat(unchanged.getVersion()).isEqualTo(version + 1);
            assertThat(unchanged.getDurationCentiseconds()).isEqualTo(100);
            assertThat(penalties.findByConfigurationIdAndAttemptCrewEntryCompetitionCategoryId(configurations.findByDisciplineId(discipline.getId()).orElseThrow().getId(), category.getId())).extracting(SlalomGatePenalty::getPenaltySeconds).containsExactly(5);
        } finally {
            cleanup(competition.getId());
        }
    }

    private void cleanup(long competitionId) {
        transactions.executeWithoutResult(status -> {
            var disciplines = "select id from disciplines where competition_id = ?";
            var attempts = "select id from attempt_results where discipline_id in (" + disciplines + ")";
            var lists = "select id from start_lists where discipline_id in (" + disciplines + ")";
            jdbc.update("delete from slalom_gate_penalties where attempt_result_id in (" + attempts + ")", competitionId);
            jdbc.update("delete from calculated_discipline_results where discipline_id in (" + disciplines + ")", competitionId);
            jdbc.update("delete from attempt_results where id in (" + attempts + ")", competitionId);
            jdbc.update("delete from start_slots where start_list_id in (" + lists + ")", competitionId);
            jdbc.update("delete from start_lists where id in (" + lists + ")", competitionId);
            jdbc.update("delete from slalom_configurations where discipline_id in (" + disciplines + ")", competitionId);
            jdbc.update("delete from crew_entries where competition_category_id in (select id from competition_categories where competition_id = ?)", competitionId);
            jdbc.update("delete from discipline_categories where discipline_id in (" + disciplines + ")", competitionId);
            jdbc.update("delete from disciplines where competition_id = ?", competitionId);
            jdbc.update("delete from competition_categories where competition_id = ?", competitionId);
            jdbc.update("delete from competitions where id = ?", competitionId);
        });
    }
}
