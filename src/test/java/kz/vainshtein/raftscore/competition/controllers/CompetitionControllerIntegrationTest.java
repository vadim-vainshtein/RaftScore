package kz.vainshtein.raftscore.competition.controllers;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompetitionControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @MockitoSpyBean CompetitionRepository repository;
    @PersistenceContext EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    private static final String BODY = "\"title\":\"Race\",\"startDate\":\"2026-05-01\",\"location\":\"River\",\"timeZone\":\"Asia/Almaty\",\"admissionConfirmationRequired\":false";

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void createsUpdatesAndRejectsStaleVersions() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.endDate").value("2026-05-01"))
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/competitions/" + id);
        mvc.perform(put("/api/competitions/{id}", id).with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY.replace("Race", "Winner") + ",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/competitions/{id}", id).with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + ",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
        assertThat(repository.findById(id.longValue()).orElseThrow().getTitle()).isEqualTo("Winner");
    }

    @Test void configuresTiebreakWithCompetitionVersionAndOwnership() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var other = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number otherId = JsonPath.read(other.getResponse().getContentAsString(), "$.id");
        var disciplineId = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order) values (?, 'Slalom', 'SLALOM', 0) returning id", Long.class, id.longValue());
        var otherDisciplineId = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order) values (?, 'Other', 'SLALOM', 0) returning id", Long.class, otherId.longValue());
        var url = "/api/competitions/" + id + "/tiebreak";
        mvc.perform(get(url).with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disciplineId\":1.5,\"version\":0}")).andExpect(status().isBadRequest());
        var body = "{\"disciplineId\":" + disciplineId + ",\"version\":0}";
        mvc.perform(put(url).with(user("reader").roles("READ_ONLY")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disciplineId\":" + otherDisciplineId + ",\"version\":0}")).andExpect(status().isBadRequest());
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disciplineId").value(disciplineId)).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get(url).with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk()).andExpect(jsonPath("$.disciplineId").value(disciplineId));
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"disciplineId\":null,\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test void rejectsInvalidAndMissingDetails() throws Exception {
        for (var body : new String[]{"{}", "{" + BODY.replace("Asia/Almaty", "Invalid/Zone") + "}",
                "{" + BODY + ",\"endDate\":\"2026-04-30\"}", "{" + BODY.replace("Race", "\\u0000") + "}"}) {
            mvc.perform(post("/api/competitions").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        mvc.perform(put("/api/competitions/99999999").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + BODY + ",\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("competition.not_found"));
        mvc.perform(put("/api/competitions/99999999").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + BODY + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test void requiresFullAccessAndCsrf() throws Exception {
        mvc.perform(post("/api/competitions").with(user("reader").roles("READ_ONLY")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}")) .andExpect(status().isForbidden());
        mvc.perform(put("/api/competitions/1").with(user("reader").roles("READ_ONLY")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + ",\"version\":0}")) .andExpect(status().isForbidden());
        mvc.perform(post("/api/competitions").with(full()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + BODY + "}")) .andExpect(status().isForbidden());
        mvc.perform(put("/api/competitions/1").with(full()).contentType(MediaType.APPLICATION_JSON)
                .content("{" + BODY + ",\"version\":0}")) .andExpect(status().isForbidden());
    }

    @Test void rejectsOutOfRangeDatesAndMalformedIds() throws Exception {
        for (var date : new String[]{"0000-01-01", "+10000-01-01"}) {
            var invalid = BODY.replace("2026-05-01", date);
            mvc.perform(post("/api/competitions").with(full()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{" + invalid + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
            mvc.perform(put("/api/competitions/1").with(full()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{" + invalid + ",\"version\":0}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        for (var version : new String[]{"0.9", "9223372036854775808"}) {
            mvc.perform(put("/api/competitions/1").with(full()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + ",\"version\":" + version + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.validation_failed"));
        }
        for (var id : new String[]{"abc", "999999999999999999999999"}) {
            mvc.perform(put("/api/competitions/{id}", id).with(full()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + ",\"version\":0}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("request.malformed"));
        }
    }

    @Test void concurrentCommitAfterReadIsReportedAsConflict() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            var loaded = Optional.ofNullable(entityManager.find(Competition.class, invocation.getArgument(0)));
            independent.executeWithoutResult(status -> jdbc.update(
                    "update competitions set title = ?, version = version + 1 where id = ?", "Concurrent winner", id.longValue()));
            return loaded;
        }).when(repository).findById(anyLong());
        mvc.perform(put("/api/competitions/{id}", id).with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + BODY.replace("Race", "Loser") + ",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
        assertThat(jdbc.queryForObject("select title from competitions where id = ?", String.class, id.longValue()))
                .isEqualTo("Concurrent winner");
    }
    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor full() {
        return user("admin").roles("FULL_ACCESS");
    }
}
