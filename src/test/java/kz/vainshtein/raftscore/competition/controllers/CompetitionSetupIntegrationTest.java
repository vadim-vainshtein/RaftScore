package kz.vainshtein.raftscore.competition.controllers;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompetitionSetupIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private static final String BODY = """
        {"competition":{"title":"Setup integration","startDate":"2026-05-01","location":"River","timeZone":"Asia/Almaty","admissionConfirmationRequired":true},
         "categories":[{"classLabel":"R4","scoringGroupLabel":"Open","crewSize":4}],
         "requirements":[{"name":"License","valueType":"TEXT","required":true,"displayOrder":0}],
         "disciplines":[{"name":"Slalom","type":"SLALOM","displayOrder":0,"categoryIndexes":[0],"gateCount":12,"attemptCount":2,
           "pointsTable":{"name":"Points","tiePolicy":"%s","rows":[{"place":1,"points":100}]}},
           {"name":"Parallel","type":"PARALLEL_SPRINT","displayOrder":1,"categoryIndexes":[0],"qualificationDisciplineIndex":0}],
         "tiebreakDisciplineIndex":1}
        """.formatted(kz.vainshtein.raftscore.scoring.entities.TiePointsPolicy.values()[0]);

    @Test @org.springframework.transaction.annotation.Transactional void createsAllSectionsAndLinks() throws Exception {
        var response = mvc.perform(post("/api/competitions/setup").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(response.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.queryForObject("select count(*) from competition_categories where competition_id=?", Integer.class, id.longValue())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from entry_requirements where competition_id=? and value_type='TEXT'", Integer.class, id.longValue())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from disciplines d join slalom_configurations s on s.discipline_id=d.id join placement_points_tables p on p.id=d.points_table_id join placement_points_rows r on r.table_id=p.id where d.competition_id=? and s.gate_count=12 and r.points=100", Integer.class, id.longValue())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from competitions c join disciplines d on d.id=c.tiebreak_discipline_id join disciplines q on q.id=d.qualification_discipline_id where c.id=?", Integer.class, id.longValue())).isEqualTo(1);
    }
    @Test @org.springframework.transaction.annotation.Transactional void snapshotsActiveTemplateWithOverrides() throws Exception {
        var template = jdbc.queryForObject("insert into category_templates (class_label, scoring_group_label, crew_size, archived) values ('R6', 'Women', 6, false) returning id", Long.class);
        var body = BODY.replace("{\"classLabel\":\"R4\",\"scoringGroupLabel\":\"Open\",\"crewSize\":4}",
                "{\"templateId\":" + template + ",\"scoringGroupLabel\":\"Mixed\"}");
        var response = mvc.perform(post("/api/competitions/setup").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(response.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.queryForObject("select count(*) from competition_categories where competition_id=? and class_label='R6' and scoring_group_label='Mixed' and crew_size=6", Integer.class, id.longValue())).isEqualTo(1);
    }
    @Test void invalidLateReferenceRollsBackAndSecurityApplies() throws Exception {
        var before = jdbc.queryForObject("select count(*) from competitions where title='Setup integration'", Integer.class);
        mvc.perform(post("/api/competitions/setup").with(user("admin").roles("FULL_ACCESS")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"tiebreakDisciplineIndex\":1", "\"tiebreakDisciplineIndex\":9")))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from competitions where title='Setup integration'", Integer.class)).isEqualTo(before);
        mvc.perform(post("/api/competitions/setup").with(user("reader").roles("READ_ONLY")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/competitions/setup").with(user("admin").roles("FULL_ACCESS")).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    }
}
