package kz.vainshtein.raftscore.competition.controllers;

import java.time.LocalDate;
import com.jayway.jsonpath.JsonPath;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplate;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class CompetitionCategoryControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CompetitionRepository competitions;
    @Autowired CategoryTemplateRepository templates;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Competition competition;
    CategoryTemplate template;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        competition = competitions.saveAndFlush(Competition.builder().title("Race").startDate(LocalDate.of(2026, 1, 1))
                .location("River").timeZone("UTC").build());
        template = templates.saveAndFlush(CategoryTemplate.builder().classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createsIndependentSnapshot() throws Exception {
        try {
            var response = mvc.perform(post("/api/competitions/{id}/categories", competition.getId())
                    .with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"templateId\":" + template.getId() + "}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.competitionId").value(competition.getId()))
                    .andExpect(jsonPath("$.classLabel").value("R4")).andExpect(jsonPath("$.scoringGroupLabel").value("Open"))
                    .andExpect(jsonPath("$.crewSize").value(4)).andExpect(jsonPath("$.displayName").value("R4 / Open"))
                    .andReturn().getResponse();
            Number id = JsonPath.read(response.getContentAsString(), "$.id");
            assertThat(response.getHeader("Location")).isEqualTo("/api/competitions/" + competition.getId() + "/categories/" + id);
            template.setClassLabel("R6");
            template.setScoringGroupLabel("Women");
            template.setCrewSize(6);
            template.setArchived(true);
            templates.saveAndFlush(template);
            var snapshot = categories.findById(id.longValue()).orElseThrow();
            assertThat(snapshot.getClassLabel()).isEqualTo("R4");
            assertThat(snapshot.getScoringGroupLabel()).isEqualTo("Open");
            assertThat(snapshot.getCrewSize()).isEqualTo(4);
        } finally {
            jdbc.update("delete from competition_categories where competition_id = ?", competition.getId());
            competitions.deleteById(competition.getId());
            templates.deleteById(template.getId());
        }
    }

    @Test
    void rejectsInvalidRequestsAndEnforcesSecurity() throws Exception {
        var url = "/api/competitions/" + competition.getId() + "/categories";
        var admin = user("admin").roles("FULL_ACCESS");
        mvc.perform(post(url).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(url).with(user("reader").roles("READ_ONLY")).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("access.forbidden"));
        mvc.perform(post(url).with(admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.forbidden"));
        for (var body : new String[]{"{}", "{\"templateId\":0}", "{\"templateId\":1.5}", "{\"templateId\":9223372036854775808}"}) {
            mvc.perform(post(url).with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        mvc.perform(post(url).with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        mvc.perform(post("/api/competitions/bad/categories").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        mvc.perform(post("/api/competitions/999999999/categories").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":1}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("competition.not_found"));
        mvc.perform(post(url).with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":999999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("category_template.not_found"));
        template.setArchived(true);
        templates.saveAndFlush(template);
        mvc.perform(post(url).with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"templateId\":" + template.getId() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category_template.archived"));
        assertThat(categories.count()).isZero();
    }

    @Test
    void documentsResponses() throws Exception {
        mvc.perform(get("/v3/api-docs").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/categories'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/categories'].post.responses['403'].content['application/json'].schema['$ref']").value(org.hamcrest.Matchers.containsString("ApiError")))
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/categories'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/categories'].post.responses['409']").exists());
    }
}
