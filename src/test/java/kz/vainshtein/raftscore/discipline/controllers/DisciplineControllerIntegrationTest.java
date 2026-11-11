package kz.vainshtein.raftscore.discipline.controllers;

import java.time.LocalDate;
import java.util.List;
import kz.vainshtein.raftscore.competition.entities.*;
import kz.vainshtein.raftscore.discipline.entities.*;
import kz.vainshtein.raftscore.scoring.entities.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class DisciplineControllerIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CompetitionRepository competitions;
    @Autowired DisciplineRepository disciplines;
    @Autowired PlacementPointsTableRepository tables;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Competition owner, other;
    Discipline first, second;
    PlacementPointsTable table, foreign;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = competitions.saveAndFlush(competition("Race"));
        other = competitions.saveAndFlush(competition("Other"));
        first = disciplines.saveAndFlush(discipline(owner, "Sprint"));
        second = disciplines.saveAndFlush(discipline(owner, "Slalom"));
        table = tables.saveAndFlush(new PlacementPointsTable(owner, null, TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of()));
        foreign = tables.saveAndFlush(new PlacementPointsTable(other, null, TiePointsPolicy.SHARED_PLACE_SAME_POINTS, List.of()));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from disciplines where competition_id in (?, ?)", owner.getId(), other.getId());
        jdbc.update("delete from placement_points_tables where competition_id in (?, ?)", owner.getId(), other.getId());
        jdbc.update("delete from competitions where id in (?, ?)", owner.getId(), other.getId());
    }

    @Test
    void commitsUpdatesAndEnforcesOwnershipAndIndependentAssignment() throws Exception {
        update(owner.getId(), first.getId(), body(3, table.getId(), 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.pointsTableId").value(table.getId()));
        assertThat(jdbc.queryForObject("select display_order from disciplines where id = ?", Integer.class, first.getId())).isEqualTo(3);
        update(owner.getId(), first.getId(), body(4, null, 0)).andExpect(status().isConflict());
        update(owner.getId(), second.getId(), body(4, table.getId(), 0)).andExpect(status().isConflict());
        update(owner.getId(), second.getId(), body(4, foreign.getId(), 0)).andExpect(status().isNotFound());
        update(other.getId(), first.getId(), body(4, null, 1)).andExpect(status().isNotFound());
        update(owner.getId(), first.getId(), body(5, null, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        update(owner.getId(), second.getId(), body(4, table.getId(), 0)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select points_table_id from disciplines where id = ?", Long.class, second.getId())).isEqualTo(table.getId());
    }

    @Test
    void validatesAndRequiresAccess() throws Exception {
        var url = "/api/competitions/" + owner.getId() + "/disciplines/" + first.getId();
        mvc.perform(put(url).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(put(url).with(user("reader").roles("READ_ONLY")).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.forbidden"));
        mvc.perform(put(url).with(user("admin").roles("FULL_ACCESS"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access.forbidden"));
        for (var payload : List.of("{}", "{", body(-1, null, 0), "{\"displayOrder\":1.5,\"version\":0}", "{\"displayOrder\":1,\"version\":0,\"pointsTableId\":0}")) {
            update(owner.getId(), first.getId(), payload).andExpect(status().isBadRequest());
        }
        update(owner.getId(), 999999999L, body(1, null, 0)).andExpect(status().isNotFound());
        update(owner.getId(), first.getId(), body(1, 999999999L, 0)).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/disciplines/{disciplineId}'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/disciplines/{disciplineId}'].put.responses['403'].content['application/json'].schema['$ref']").value(org.hamcrest.Matchers.containsString("ApiError")))
                .andExpect(jsonPath("$.paths['/api/competitions/{competitionId}/disciplines/{disciplineId}'].put.responses['200'].content['application/json'].schema['$ref']").value(org.hamcrest.Matchers.containsString("DisciplineResponse")));
    }

    private org.springframework.test.web.servlet.ResultActions update(long competition, long discipline, String payload) throws Exception {
        return mvc.perform(put("/api/competitions/{competitionId}/disciplines/{disciplineId}", competition, discipline)
                .with(user("admin").roles("FULL_ACCESS")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload));
    }
    private String body(int order, Long tableId, long version) {
        return "{\"displayOrder\":" + order + ",\"pointsTableId\":" + tableId + ",\"version\":" + version + "}";
    }
    private Competition competition(String title) {
        return Competition.builder().title(title).startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC").build();
    }
    private Discipline discipline(Competition competition, String name) {
        return Discipline.builder().competition(competition).name(name).type(DisciplineType.SPRINT).displayOrder(0).build();
    }
}
