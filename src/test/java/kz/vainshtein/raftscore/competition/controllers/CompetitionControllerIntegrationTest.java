package kz.vainshtein.raftscore.competition.controllers;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

    @Test @org.springframework.transaction.annotation.Transactional
    void transitionsRequireCurrentVersionAndCloseResultWrites() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED")).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var details = "/api/competitions/" + id;
        var transition = details + "/status";
        var recalculate = details + "/recalculate";

        mvc.perform(post(recalculate).with(full()).with(csrf())).andExpect(status().isConflict());
        mvc.perform(put(transition).with(user("reader").roles("READ_ONLY")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(statusChange("STARTED", 0)))
                .andExpect(status().isForbidden());
        mvc.perform(put(transition).with(full()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 0)))
                .andExpect(status().isConflict());
        mvc.perform(put(transition).with(full()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(statusChange("STARTED", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(recalculate).with(full()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(details + "/completion-issues").with(full())).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("noDisciplines"));
        mvc.perform(put(transition).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andExpect(status().isConflict());
        var discipline = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Empty', 'SPRINT', 0, 'CREATED') returning id", Long.class, id.longValue());
        mvc.perform(get(details + "/completion-issues").with(full())).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("noParticipatingCategories"));
        mvc.perform(put(transition).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andExpect(status().isConflict());
        var category = jdbc.queryForObject("insert into competition_categories (competition_id, class_label, scoring_group_label, crew_size) values (?, 'R1', 'Open', 1) returning id", Long.class, id.longValue());
        jdbc.update("insert into discipline_categories (discipline_id, category_id) values (?, ?)", discipline, category);
        jdbc.update("insert into crew_entries (competition_category_id, crew_name, region_or_club, representative, start_number) values (?, 'Not eligible', '', '', 1)", category);
        var unused = jdbc.queryForObject("insert into competition_categories (competition_id, class_label, scoring_group_label, crew_size) values (?, 'Unused', 'Open', 1) returning id", Long.class, id.longValue());
        var unusedCrew = jdbc.queryForObject("insert into crew_entries (competition_category_id, crew_name, region_or_club, representative, start_number) values (?, 'Unused vessel', '', '', 2) returning id", Long.class, unused);
        var unusedPerson = jdbc.queryForObject("insert into participants (surname, given_name) values ('Unused', 'Person') returning id", Long.class);
        jdbc.update("insert into crew_memberships (crew_entry_id, competition_category_id, participant_id, position) values (?, ?, ?, 1)", unusedCrew, unused, unusedPerson);
        entityManager.clear();
        mvc.perform(get(details + "/completion-issues").with(full())).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("noEligibleCrews"));
        mvc.perform(put(transition).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andExpect(status().isConflict());
        mvc.perform(put(transition).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("conflict.optimistic_lock"));
        mvc.perform(get(details).with(user("reader").roles("READ_ONLY"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STARTED"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DNS", "DNF", "DSQ_ATTEMPT", "DSQ_DISCIPLINE", "DSQ_COMPETITION"})
    @org.springframework.transaction.annotation.Transactional
    void incompleteResultsBlockBothClosuresUntilExplicitNonfinishIsRecorded(String nonfinish) throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var competitionId = id.longValue();
        var category = jdbc.queryForObject("insert into competition_categories (competition_id, class_label, scoring_group_label, crew_size) values (?, 'R1', 'Open', 1) returning id", Long.class, competitionId);
        var discipline = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Sprint', 'SPRINT', 0, 'STARTED') returning id", Long.class, competitionId);
        jdbc.update("insert into discipline_categories (discipline_id, category_id) values (?, ?)", discipline, category);
        var emptyCategory = jdbc.queryForObject("insert into competition_categories (competition_id, class_label, scoring_group_label, crew_size) values (?, 'R2', 'Open', 1) returning id", Long.class, competitionId);
        jdbc.update("insert into discipline_categories (discipline_id, category_id) values (?, ?)", discipline, emptyCategory);
        var list = jdbc.queryForObject("insert into start_lists (discipline_id, category_id) values (?, ?) returning id", Long.class, discipline, category);
        long[] crew = new long[2];
        for (int i = 0; i < 2; i++) {
            crew[i] = jdbc.queryForObject("insert into crew_entries (competition_category_id, crew_name, region_or_club, representative, start_number) values (?, ?, '', '', ?) returning id", Long.class, category, "Crew " + (i + 1), i + 1);
            var participant = jdbc.queryForObject("insert into participants (surname, given_name) values (?, 'Test') returning id", Long.class, "Participant " + i);
            jdbc.update("insert into crew_memberships (crew_entry_id, competition_category_id, participant_id, position) values (?, ?, ?, 1)", crew[i], category, participant);
            jdbc.update("insert into start_slots (start_list_id, category_id, crew_entry_id, position) values (?, ?, ?, ?)", list, category, crew[i], i + 1);
        }
        var otherDiscipline = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Long race', 'LONG_RACE', 1, 'STARTED') returning id", Long.class, competitionId);
        jdbc.update("insert into discipline_categories (discipline_id, category_id) values (?, ?)", otherDiscipline, category);
        var otherList = jdbc.queryForObject("insert into start_lists (discipline_id, category_id) values (?, ?) returning id", Long.class, otherDiscipline, category);
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into start_slots (start_list_id, category_id, crew_entry_id, position) values (?, ?, ?, ?)", otherList, category, crew[i], i + 1);
            jdbc.update("insert into attempt_results (discipline_id, crew_entry_id, attempt_number, duration_centiseconds, penalty_centiseconds, status) values (?, ?, 1, 100, 0, 'FINISHED')", otherDiscipline, crew[i]);
        }
        var unusedDiscipline = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Unused', 'SPRINT', 2, 'STARTED') returning id", Long.class, competitionId);
        var base = "/api/competitions/" + id;
        mvc.perform(put(base + "/status").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("STARTED", 0))).andExpect(status().isOk());
        var attempts = base + "/disciplines/" + discipline + "/categories/" + category + "/crews/";
        mvc.perform(put(attempts + crew[0] + "/attempts/1").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"durationCentiseconds\":100,\"penaltyCentiseconds\":0,\"status\":\"FINISHED\"}")).andExpect(status().isOk());
        mvc.perform(get(base + "/completion-issues").with(full())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].crewId").value(crew[1]));
        mvc.perform(put(base + "/disciplines/" + discipline + "/status").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(statusChange("COMPLETED", 0))).andExpect(status().isConflict());
        mvc.perform(put(base + "/status").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select status from disciplines where id in (?, ?) order by id", String.class, discipline, otherDiscipline)).containsExactly("STARTED", "STARTED");
        mvc.perform(put(attempts + crew[1] + "/attempts/1").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"penaltyCentiseconds\":0,\"status\":\"" + nonfinish + "\"}")).andExpect(status().isOk());

        mvc.perform(put(base + "/status").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("select status from disciplines where id in (?, ?) order by id", String.class, discipline, otherDiscipline)).containsExactly("COMPLETED", "COMPLETED");
        assertThat(jdbc.queryForObject("select status from disciplines where id = ?", String.class, unusedDiscipline)).isEqualTo("STARTED");
    }
    @Test
    void completionWaitsForAnInFlightResultAndChecksItsCommittedValue() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var category = jdbc.queryForObject("insert into competition_categories (competition_id, class_label, scoring_group_label, crew_size) values (?, 'R1', 'Open', 1) returning id", Long.class, id.longValue());
        var discipline = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Sprint', 'SPRINT', 0, 'STARTED') returning id", Long.class, id.longValue());
        jdbc.update("insert into discipline_categories (discipline_id, category_id) values (?, ?)", discipline, category);
        var crew = jdbc.queryForObject("insert into crew_entries (competition_category_id, crew_name, region_or_club, representative, start_number) values (?, 'Lock vessel', '', '', 1) returning id", Long.class, category);
        var person = jdbc.queryForObject("insert into participants (surname, given_name) values ('Test', 'Person') returning id", Long.class);
        jdbc.update("insert into crew_memberships (crew_entry_id, competition_category_id, participant_id, position) values (?, ?, ?, 1)", crew, category, person);
        var list = jdbc.queryForObject("insert into start_lists (discipline_id, category_id) values (?, ?) returning id", Long.class, discipline, category);
        jdbc.update("insert into start_slots (start_list_id, category_id, crew_entry_id, position) values (?, ?, ?, 1)", list, category, crew);
        var url = "/api/competitions/" + id + "/status";
        mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("STARTED", 0))).andExpect(status().isOk());
        var locked = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from competitions where id = ? for update", Long.class, id.longValue());
                jdbc.update("insert into attempt_results (discipline_id, crew_entry_id, attempt_number, duration_centiseconds, penalty_centiseconds, status) values (?, ?, 1, 100, 0, 'FINISHED')", discipline, crew);
                locked.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var closing = pool.submit(() -> mvc.perform(put(url).with(full()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(statusChange("COMPLETED", 1))).andReturn());
            try { closing.get(300, java.util.concurrent.TimeUnit.MILLISECONDS); throw new AssertionError("completion did not wait for lock"); }
            catch (java.util.concurrent.TimeoutException expected) { }
            release.countDown(); writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(closing.get(10, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select status from disciplines where id = ?", String.class, discipline)).isEqualTo("COMPLETED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private static String statusChange(String status, long version) {
        return "{\"status\":\"%s\",\"version\":%d}".formatted(status, version);
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
        var disciplineId = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Slalom', 'SLALOM', 0, 'CREATED') returning id", Long.class, id.longValue());
        var otherDisciplineId = jdbc.queryForObject("insert into disciplines (competition_id, name, type, display_order, status) values (?, 'Other', 'SLALOM', 0, 'CREATED') returning id", Long.class, otherId.longValue());
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

    @Test void concurrentCommitBeforeLockIsReportedAsConflict() throws Exception {
        var created = mvc.perform(post("/api/competitions").with(full()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{" + BODY + "}"))
                .andExpect(status().isCreated()).andReturn();
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            independent.executeWithoutResult(status -> jdbc.update(
                    "update competitions set title = ?, version = version + 1 where id = ?", "Concurrent winner", id.longValue()));
            return Optional.ofNullable(entityManager.find(Competition.class, invocation.getArgument(0), LockModeType.PESSIMISTIC_WRITE));
        }).when(repository).findForDisciplineConfiguration(anyLong());
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
