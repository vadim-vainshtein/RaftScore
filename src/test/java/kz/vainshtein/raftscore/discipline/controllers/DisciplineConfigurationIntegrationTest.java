package kz.vainshtein.raftscore.discipline.controllers;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.slalom.entities.*;
import kz.vainshtein.raftscore.startlist.entities.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class DisciplineConfigurationIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired DisciplineRepository disciplines;
    @Autowired SlalomConfigurationRepository configurations;
    @Autowired WebApplicationContext context;
    @Autowired StartListRepository startLists;

    @Test void configPersistsAndEnforcesScopeVersionsAndSecurity() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category)); discipline = disciplines.saveAndFlush(discipline);
        configurations.saveAndFlush(new SlalomConfiguration(discipline, 3, 2));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var base = "/api/competitions/" + competition.getId() + "/disciplines";
        var url = base + "/" + discipline.getId() + "/configuration";
        var json = "{\"name\":\"Slalom\",\"type\":\"SLALOM\",\"displayOrder\":1,\"categoryIds\":[" + category.getId() + "],\"gateCount\":5,\"attemptCount\":3,\"version\":" + discipline.getVersion() + "}";
        mvc.perform(get(base).with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].gateCount").value(3));
        mvc.perform(put(url).with(user("reader").roles("READ_ONLY")).with(csrf()).contentType("application/json").content(json)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).contentType("application/json").content(json)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType("application/json").content(json.replace("\"gateCount\":5", "\"gateCount\":5.5"))).andExpect(status().isBadRequest());
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType("application/json").content(json)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(discipline.getVersion() + 1));
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType("application/json").content(json)).andExpect(status().isConflict());
        var configOnly = json.replace("\"version\":" + discipline.getVersion(), "\"version\":" + (discipline.getVersion() + 1)).replace("\"gateCount\":5", "\"gateCount\":6");
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType("application/json").content(configOnly)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(discipline.getVersion() + 2));
        assertThat(configurations.findByDisciplineId(discipline.getId()).orElseThrow().getGateCount()).isEqualTo(6);
        mvc.perform(get("/api/competitions/999999/disciplines").with(user("reader").roles("READ_ONLY"))).andExpect(status().isNotFound());
    }
    @Test void recordedActivityPreventsChangingSlalomCounts() throws Exception {
        var competition = competitions.saveAndFlush(Competition.builder().title("Guard").startDate(LocalDate.of(2026, 7, 1)).location("River").timeZone("UTC").build());
        var category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        var discipline = disciplines.saveAndFlush(Discipline.builder().competition(competition).name("Slalom").type(DisciplineType.SLALOM).build());
        discipline.replaceCategories(List.of(category)); discipline = disciplines.saveAndFlush(discipline);
        configurations.saveAndFlush(new SlalomConfiguration(discipline, 3, 2));
        startLists.saveAndFlush(StartList.builder().discipline(discipline).category(category).build());
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var url = "/api/competitions/" + competition.getId() + "/disciplines/" + discipline.getId() + "/configuration";
        var json = "{\"name\":\"Slalom\",\"type\":\"SLALOM\",\"displayOrder\":0,\"categoryIds\":[" + category.getId() + "],\"gateCount\":5,\"attemptCount\":2,\"version\":" + discipline.getVersion() + "}";
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType("application/json").content(json)).andExpect(status().isConflict());
        assertThat(configurations.findByDisciplineId(discipline.getId()).orElseThrow().getGateCount()).isEqualTo(3);
    }

}
