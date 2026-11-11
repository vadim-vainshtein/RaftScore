package kz.vainshtein.raftscore.auth;

import java.time.LocalDate;
import java.util.stream.Stream;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import kz.vainshtein.raftscore.competition.entities.Competition;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategory;
import kz.vainshtein.raftscore.competition.entities.CompetitionCategoryRepository;
import kz.vainshtein.raftscore.competition.entities.CompetitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class AuthorizationMatrixIntegrationTest extends PostgreSqlIntegrationTest {
    private static final String PASSWORD = "integration-test-initial-password";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionCategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    MockHttpSession reader;
    MockHttpSession admin;
    long competitionId;
    long categoryId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        users.saveAndFlush(User.builder().username("matrix-reader").passwordHash(encoder.encode(PASSWORD))
                .role(UserRole.READ_ONLY).enabled(true).build());
        var competition = competitions.saveAndFlush(Competition.builder().title("Authorization matrix")
                .startDate(LocalDate.of(2026, 1, 1)).location("River").timeZone("UTC")
                .admissionConfirmationRequired(true).build());
        competitionId = competition.getId();
        categoryId = categories.saveAndFlush(CompetitionCategory.builder().competition(competition)
                .classLabel("R4").scoringGroupLabel("Open").crewSize(1).build()).getId();
        reader = login("matrix-reader");
        admin = login("admin");
    }

    record Mutation(HttpMethod method, String path) { }
    static Stream<Mutation> mutations() {
        return Stream.of(
                new Mutation(HttpMethod.POST, "/api/competitions/setup"),
                new Mutation(HttpMethod.POST, "/api/competitions"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d"),
                new Mutation(HttpMethod.POST, "/api/competitions/%d/categories"),
                new Mutation(HttpMethod.POST, "/api/competitions/%d/disciplines"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/disciplines/1/configuration"),
                new Mutation(HttpMethod.POST, "/api/competitions/%d/entries"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/entries/1"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/entries/1/admission"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/disciplines/1/categories/1/start-list"),
                new Mutation(HttpMethod.DELETE, "/api/competitions/%d/disciplines/1/categories/1/start-list/source-results"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/disciplines/1/categories/1/crews/1/attempts/1"),
                new Mutation(HttpMethod.POST, "/api/competitions/%d/recalculate"),
                new Mutation(HttpMethod.PUT, "/api/competitions/%d/disciplines/1/categories/1/brackets/1/heats/1/result"),
                new Mutation(HttpMethod.POST, "/api/backups"),
                new Mutation(HttpMethod.POST, "/api/backups/restore"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    void readerCannotMutateWithCsrf(Mutation mutation) throws Exception {
        var before = jdbc.queryForObject("select count(*) from competitions", Long.class);
        mvc.perform(request(mutation.method(), mutation.path().formatted(competitionId)).session(reader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from competitions", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select version from competitions where id = ?", Long.class, competitionId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from crew_entries where competition_category_id = ?", Long.class, categoryId)).isZero();
    }

    @Test
    void readerSeesPublicProjectionButNotRosterOrBackups() throws Exception {
        var entries = "/api/competitions/" + competitionId + "/entries";
        var body = "{\"categoryId\":" + categoryId + ",\"crewName\":\"Crew\",\"regionOrClub\":\"Club\",\"representative\":\"Coach\",\"startNumber\":1,\"roster\":[{\"surname\":\"Private\",\"givenName\":\"Person\"}]}";
        var created = mvc.perform(post(entries).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        Number entryId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(get(entries).session(reader)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].crewName").value("Crew"))
                .andExpect(jsonPath("$[0].roster").doesNotExist())
                .andExpect(jsonPath("$[0].representative").doesNotExist());
        mvc.perform(get(entries + "/private").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get(entries + "/" + entryId).session(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/backups").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/backups/00000000-0000-0000-0000-000000000001/download").session(reader)).andExpect(status().isForbidden());
        mvc.perform(get(entries + "/private").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roster[0].surname").value("Private"));
        mvc.perform(get("/api/competitions/" + competitionId + "/categories").session(reader)).andExpect(status().isOk());
    }

    @Test
    void fullAccessMutationRequiresCsrf() throws Exception {
        var body = "{\"categoryId\":" + categoryId + ",\"crewName\":\"Crew\",\"regionOrClub\":\"Club\",\"representative\":\"Coach\",\"startNumber\":1,\"roster\":[{\"surname\":\"A\",\"givenName\":\"B\"}]}";
        var url = "/api/competitions/" + competitionId + "/entries";
        mvc.perform(post(url).session(admin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from crew_entries where competition_category_id = ?", Long.class, categoryId)).isZero();
        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from crew_entries where competition_category_id = ?", Long.class, categoryId)).isEqualTo(1);
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
