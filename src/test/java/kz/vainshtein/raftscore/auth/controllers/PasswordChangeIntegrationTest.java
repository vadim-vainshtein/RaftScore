package kz.vainshtein.raftscore.auth.controllers;

import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import kz.vainshtein.raftscore.auth.services.PasswordHashingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PasswordChangeIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordHashingService passwords;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cleanUp();
        users.save(User.builder().username("password-reader").passwordHash(passwords.hash("old-password"))
                .role(UserRole.READ_ONLY).enabled(true).build());
        users.save(User.builder().username("password-admin").passwordHash(passwords.hash("old-password"))
                .role(UserRole.FULL_ACCESS).enabled(true).build());
    }

    @AfterEach
    void cleanUp() {
        users.findByUsername("password-reader").ifPresent(users::delete);
        users.findByUsername("password-admin").ifPresent(users::delete);
    }

    @Test
    void bothRolesCanChangeOnlyTheirOwnPasswordAndUseItToLogIn() throws Exception {
        for (var username : new String[]{"password-reader", "password-admin"}) {
            var session = login(username, "old-password", 204);
            var other = username.equals("password-reader") ? "password-admin" : "password-reader";
            var otherHash = users.findByUsername(other).orElseThrow().getPasswordHash();
            var before = users.findByUsername(username).orElseThrow();
            mvc.perform(put("/api/auth/password").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\",\"username\":\"" + other + "\"}"))
                    .andExpect(status().isNoContent());
            var updated = users.findByUsername(username).orElseThrow();
            assertThat(updated.getVersion()).isGreaterThan(before.getVersion());
            assertThat(users.findByUsername(other).orElseThrow().getPasswordHash()).isEqualTo(otherHash);
            login(username, "old-password", 401);
            login(username, "new-password", 204);
        }
    }

    @Test
    void rejectsWrongInvalidUnauthenticatedAndCsrfFreeRequests() throws Exception {
        var session = login("password-reader", "old-password", 204);
        var originalHash = users.findByUsername("password-reader").orElseThrow().getPasswordHash();
        var valid = "{\"currentPassword\":\"old-password\",\"newPassword\":\"new-password\"}";
        mvc.perform(put("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/password").session(session).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden());
        for (var body : new String[]{"{\"currentPassword\":\"wrong\",\"newPassword\":\"new\"}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":\" \"}",
                "{\"currentPassword\":\"old-password\",\"newPassword\":\"" + "é".repeat(37) + "\"}"}) {
            mvc.perform(put("/api/auth/password").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(body.contains("wrong") ? status().isUnauthorized() : status().isBadRequest());
        }
        assertThat(users.findByUsername("password-reader").orElseThrow().getPasswordHash()).isEqualTo(originalHash);
    }

    private MockHttpSession login(String username, String password, int expectedStatus) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", password))
                .andExpect(status().is(expectedStatus)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
