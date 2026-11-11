package kz.vainshtein.raftscore.entry.controllers;

import java.time.LocalDate;
import com.jayway.jsonpath.JsonPath;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.entry.entities.CrewEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class CrewEntryControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired CrewEntryRepository entries;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Competition competition;
    Competition other;
    CompetitionCategory category;
    String url;
    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        other = competitions.saveAndFlush(Competition.builder().title("Other").startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build());
        category = categories.saveAndFlush(CompetitionCategory.builder().competition(competition).classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        url = "/api/competitions/" + competition.getId() + "/entries";
    }
    String createBody() { return "{\"categoryId\":" + category.getId() + ",\"crewName\":\"Crew\",\"regionOrClub\":\"Club\",\"representative\":\"Coach\",\"startNumber\":1}"; }
    String updateBody(String version) { return "{\"crewName\":\"Changed\",\"regionOrClub\":\"Region\",\"representative\":\"Rep\",\"startNumber\":2,\"version\":" + version + "}"; }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createsUpdatesAndRejectsStaleOrWrongCompetition() throws Exception {
        try {
            var response = mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.categoryId").value(category.getId())).andExpect(jsonPath("$.version").value(0)).andReturn().getResponse();
            Number id = JsonPath.read(response.getContentAsString(), "$.id");
            assertThat(response.getHeader("Location")).isEqualTo(url + "/" + id);
            mvc.perform(put(url + "/" + id).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateBody("0")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.crewName").value("Changed")).andExpect(jsonPath("$.version").value(1));
            var staleBody = updateBody("0").replace("Changed", "Stale").replace("Region", "Old region")
                    .replace("Rep", "Old rep").replace("\"startNumber\":2", "\"startNumber\":3");
            mvc.perform(put(url + "/" + id).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(staleBody))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
            mvc.perform(put("/api/competitions/" + other.getId() + "/entries/" + id).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateBody("1")))
                    .andExpect(status().isNotFound());
            var persisted = entries.findById(id.longValue()).orElseThrow();
            assertThat(persisted.getCrewName()).isEqualTo("Changed");
            assertThat(persisted.getRegionOrClub()).isEqualTo("Region");
            assertThat(persisted.getRepresentative()).isEqualTo("Rep");
            assertThat(persisted.getStartNumber()).isEqualTo(2);
            assertThat(persisted.getVersion()).isEqualTo(1);
        } finally {
            jdbc.update("delete from crew_entries where competition_category_id = ?", category.getId());
            categories.deleteById(category.getId());
            competitions.deleteById(competition.getId());
            competitions.deleteById(other.getId());
        }
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateStartNumbersConflictWithinCategoryAndPreserveEntries() throws Exception {
        var secondCategory = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R6").scoringGroupLabel("Open").crewSize(6).build());
        try {
            var first = mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            Number firstId = JsonPath.read(first.getContentAsString(), "$.id");
            var second = mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(createBody().replace("\"startNumber\":1", "\"startNumber\":2")))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            Number secondId = JsonPath.read(second.getContentAsString(), "$.id");
            mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("crew_entry.start_number_duplicate"));
            mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(createBody().replace("\"categoryId\":" + category.getId(), "\"categoryId\":" + secondCategory.getId())))
                    .andExpect(status().isCreated());
            var duplicate = updateBody("0").replace("\"startNumber\":2", "\"startNumber\":1");
            mvc.perform(put(url + "/" + secondId).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(duplicate))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("crew_entry.start_number_duplicate"));
            mvc.perform(put(url + "/" + secondId).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(duplicate.replace("\"version\":0", "\"version\":9")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
            assertThat(entries.findById(firstId.longValue()).orElseThrow().getStartNumber()).isOne();
            var unchanged = entries.findById(secondId.longValue()).orElseThrow();
            assertThat(unchanged.getStartNumber()).isEqualTo(2);
            assertThat(unchanged.getCrewName()).isEqualTo("Crew");
            assertThat(unchanged.getVersion()).isZero();
        } finally {
            jdbc.update("delete from crew_entries where competition_category_id in (?, ?)", category.getId(), secondCategory.getId());
            categories.deleteById(secondCategory.getId());
            categories.deleteById(category.getId());
            competitions.deleteById(competition.getId());
            competitions.deleteById(other.getId());
        }
    }
    @Test
    void validatesAndEnforcesSecurity() throws Exception {
        mvc.perform(post(url).with(user("reader").roles("READ_ONLY")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isForbidden());
        mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        for (var body : new String[]{"{}", createBody().replace("\"startNumber\":1", "\"startNumber\":1.5"), createBody().replace("Crew", " ")}) {
            mvc.perform(post(url).with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        mvc.perform(post("/api/competitions/" + other.getId() + "/entries").with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isNotFound());
        mvc.perform(put(url + "/1").with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(updateBody("1.5"))).andExpect(status().isBadRequest());
        mvc.perform(get("/v3/api-docs").with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/entries'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/entries/{entryId}'].put.responses['409']").exists());
    }
}
