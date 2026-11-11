package kz.vainshtein.raftscore.catalog.controllers;

import com.jayway.jsonpath.JsonPath;
import kz.vainshtein.raftscore.catalog.entities.CategoryTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CategoryTemplateControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CategoryTemplateRepository repository;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createsEditsArchivesAndRestoresWithVersionChecksAndPaging() throws Exception {
        var response = mvc.perform(post("/api/category-templates").with(user("admin").roles("FULL_ACCESS"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"classLabel":"R4","scoringGroupLabel":"Open","crewSize":4}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.displayName").value("R4 / Open"))
                .andReturn().getResponse();
        Number id = JsonPath.read(response.getContentAsString(), "$.id");
        assertThat(response.getHeader("Location")).isEqualTo("/api/category-templates/" + id);
        mvc.perform(put("/api/category-templates/{id}", id).with(user("admin").roles("FULL_ACCESS"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"classLabel\":\"R6\",\"scoringGroupLabel\":\"Women\",\"crewSize\":6,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch("/api/category-templates/{id}/archive", id).with(user("admin").roles("FULL_ACCESS"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
        mvc.perform(patch("/api/category-templates/{id}/archive", id).with(user("admin").roles("FULL_ACCESS"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/category-templates?archived=true&size=1").with(user("admin").roles("FULL_ACCESS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.intValue()));
        mvc.perform(patch("/api/category-templates/{id}/restore", id).with(user("admin").roles("FULL_ACCESS"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void duplicateLookupAndGetRespectExactValuesAndAuthorization() throws Exception {
        var entity = repository.saveAndFlush(kz.vainshtein.raftscore.catalog.entities.CategoryTemplate.builder()
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).archived(true).build());
        var admin = user("admin").roles("FULL_ACCESS");
        var reader = user("reader").roles("READ_ONLY");
        mvc.perform(get("/api/category-templates/duplicate").param("classLabel", "R4").param("scoringGroupLabel", "Open").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true));
        mvc.perform(get("/api/category-templates/duplicate").param("classLabel", "R4").param("scoringGroupLabel", "Open")
                .param("excludeId", entity.getId().toString()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
        mvc.perform(get("/api/category-templates/duplicate").param("classLabel", "r4").param("scoringGroupLabel", "Open").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
        mvc.perform(get("/api/category-templates/{id}", entity.getId()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(entity.getId()))
                .andExpect(jsonPath("$.archived").value(true));
        mvc.perform(get("/api/category-templates/duplicate").param("classLabel", "R4").param("scoringGroupLabel", "Open").with(reader))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/category-templates/{id}", entity.getId()).with(reader)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2.5"})
    void rejectsInvalidCrewSizesOnCreateAndEdit(String crewSize) throws Exception {
        var existing = repository.saveAndFlush(kz.vainshtein.raftscore.catalog.entities.CategoryTemplate.builder()
                .classLabel("R4").scoringGroupLabel("Open").crewSize(4).build());
        var principal = user("admin").roles("FULL_ACCESS");
        var create = "{\"classLabel\":\"R6\",\"scoringGroupLabel\":\"Women\",\"crewSize\":" + crewSize + "}";
        var edit = "{\"classLabel\":\"R6\",\"scoringGroupLabel\":\"Women\",\"crewSize\":" + crewSize + ",\"version\":0}";

        mvc.perform(post("/api/category-templates").with(principal).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("crewSize"));
        mvc.perform(put("/api/category-templates/{id}", existing.getId()).with(principal).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("crewSize"));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(existing.getId())).hasValueSatisfying(found -> {
            assertThat(found.getClassLabel()).isEqualTo("R4");
            assertThat(found.getScoringGroupLabel()).isEqualTo("Open");
            assertThat(found.getCrewSize()).isEqualTo(4);
            assertThat(found.getVersion()).isZero();
        });
    }

    @Test
    void rejectsFractionalVersionsAndMalformedParameters() throws Exception {
        var principal = user("admin").roles("FULL_ACCESS");
        var edit = "{\"classLabel\":\"R4\",\"scoringGroupLabel\":\"Open\",\"crewSize\":4,\"version\":0.9}";
        mvc.perform(put("/api/category-templates/1").with(principal).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        for (var action : new String[]{"archive", "restore"}) {
            mvc.perform(patch("/api/category-templates/1/" + action).with(principal).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0.9}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        for (var query : new String[]{"page=bad", "size=bad", "archived=bad"}) {
            mvc.perform(get("/api/category-templates?" + query).with(principal))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        }
        mvc.perform(patch("/api/category-templates/bad/archive").with(principal).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
    }
    @Test
    void documentsCategoryContract() throws Exception {
        mvc.perform(get("/v3/api-docs").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/category-templates'].get.responses['200'].content['application/json'].schema['$ref']").value(containsString("TemplatePage")))
                .andExpect(jsonPath("$.paths['/api/category-templates'].get.parameters[?(@.name == 'page')].schema.minimum").value(org.hamcrest.Matchers.hasItem(0)))
                .andExpect(jsonPath("$.paths['/api/category-templates'].get.parameters[?(@.name == 'size')].schema.minimum").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.paths['/api/category-templates'].get.parameters[?(@.name == 'size')].schema.maximum").value(org.hamcrest.Matchers.hasItem(100)))
                .andExpect(jsonPath("$.paths['/api/category-templates'].get.parameters[?(@.name == 'page')].description").value(org.hamcrest.Matchers.hasItem(containsString("2147483647"))))
                .andExpect(jsonPath("$.paths['/api/category-templates'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/category-templates/{id}'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/category-templates/{id}'].put.description").value(containsString("CSRF")))
                .andExpect(jsonPath("$.components.schemas.Edit.properties.crewSize.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.Edit.properties.crewSize.minimum").value(1))
                .andExpect(jsonPath("$.components.schemas.Edit.properties.version.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.Edit.properties.version.minimum").value(0));
    }
    @Test
    void enforcesAccessCsrfAndValidation() throws Exception {
        mvc.perform(get("/api/category-templates")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/category-templates").with(user("reader").roles("READ_ONLY"))).andExpect(status().isForbidden());
        mvc.perform(head("/api/category-templates").with(user("reader").roles("READ_ONLY"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/category-templates/1/restore").with(user("reader").roles("READ_ONLY")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/category-templates").with(user("admin").roles("FULL_ACCESS")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/category-templates").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"classLabel\":\" \",\"scoringGroupLabel\":\"Open\",\"crewSize\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        mvc.perform(get("/api/category-templates?size=101").with(user("admin").roles("FULL_ACCESS")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/category-templates/999999").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"classLabel\":\"R4\",\"scoringGroupLabel\":\"Open\",\"crewSize\":4,\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("category_template.not_found"));
        mvc.perform(post("/api/category-templates").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"classLabel\":\"R\\u00004\",\"scoringGroupLabel\":\"Open\",\"crewSize\":4}"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }
}
