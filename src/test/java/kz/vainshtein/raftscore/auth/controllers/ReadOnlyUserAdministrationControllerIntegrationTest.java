package kz.vainshtein.raftscore.auth.controllers;

import com.jayway.jsonpath.JsonPath;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import kz.vainshtein.raftscore.auth.services.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ReadOnlyUserAdministrationControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHashingService passwordHashingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void fullAccessUserCanCreateResetAndBlockAReadOnlyUser() throws Exception {
        var username = "readonly/administration";

        var creation = mockMvc.perform(post("/api/users")
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"initial-password\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn();

        var createdUser = userRepository.findByUsername(username).orElseThrow();
        assertThat(creation.getResponse().getHeader("Location")).isEqualTo("/api/users/" + createdUser.getId());
        assertThat(createdUser.getRole()).isEqualTo(UserRole.READ_ONLY);
        assertThat(createdUser.isEnabled()).isTrue();
        assertThat(passwordHashingService.matches("initial-password", createdUser.getPasswordHash())).isTrue();

        mockMvc.perform(put("/api/users/{id}/password", createdUser.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"replacement-password\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/users/{id}/block", createdUser.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());

        var updatedUser = userRepository.findByUsername(username).orElseThrow();
        assertThat(updatedUser.isEnabled()).isFalse();
        assertThat(passwordHashingService.matches("replacement-password", updatedUser.getPasswordHash())).isTrue();
    }

    @Test
    void readOnlyUserCannotAdministerUsers() throws Exception {
        var readOnlyUser = SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY");

        mockMvc.perform(post("/api/users")
                        .with(readOnlyUser)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"forbidden-user\",\"password\":\"password\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/users/forbidden-user/password")
                        .with(readOnlyUser)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/users/forbidden-user/block")
                        .with(readOnlyUser)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsBlankCredentialsWithoutChangingUsers() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\" \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByUsername(" ")).isEmpty();

        mockMvc.perform(post("/api/users")
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing-password\"}"))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByUsername("missing-password")).isEmpty();

        var user = userRepository.saveAndFlush(User.builder()
                .username("validation-readonly")
                .passwordHash(passwordHashingService.hash("original-password"))
                .role(UserRole.READ_ONLY)
                .enabled(true)
                .build());

        mockMvc.perform(put("/api/users/{id}/password", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\" \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/users/{id}/password", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(passwordHashingService.matches(
                "original-password", userRepository.findByUsername(user.getUsername()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void rejectsUsernameContainingNullCharacter() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"invalid\\u0000name\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test
    void rejectsPasswordsOverBcryptByteLimitWithoutChangingUsers() throws Exception {
        var username = "byte-limit-user";
        var overlongAscii = "a".repeat(73);
        mockMvc.perform(post("/api/users")
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, overlongAscii)))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findByUsername(username)).isEmpty();

        var user = userRepository.saveAndFlush(User.builder()
                .username(username)
                .passwordHash(passwordHashingService.hash("original-password"))
                .role(UserRole.READ_ONLY)
                .enabled(true)
                .build());
        var overlongMultibyte = "\\u00e9".repeat(37);
        mockMvc.perform(put("/api/users/{id}/password", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(overlongMultibyte)))
                .andExpect(status().isBadRequest());
        assertThat(passwordHashingService.matches("original-password",
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();

        var boundaryPassword = "\\u00e9".repeat(36);
        mockMvc.perform(put("/api/users/{id}/password", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(boundaryPassword)))
                .andExpect(status().isNoContent());
    }
    @Test
    void blockingAUserInvalidatesTheirExistingSession() throws Exception {
        var username = "session-readonly";
        var user = userRepository.saveAndFlush(User.builder()
                .username(username)
                .passwordHash(passwordHashingService.hash("reader-password"))
                .role(UserRole.READ_ONLY)
                .enabled(true)
                .build());
        var csrf = csrf();
        var login = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"reader-password\"}".formatted(username)))
                .andExpect(status().isNoContent())
                .andReturn();
        var userSession = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(patch("/api/users/{id}/block", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/principal").session(userSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullAccessUserCannotModifyAnotherFullAccessUserThroughReadOnlyAdministration() throws Exception {
        var username = "full-access-administration";
        var user = userRepository.saveAndFlush(User.builder()
                .username(username)
                .passwordHash(passwordHashingService.hash("password"))
                .role(UserRole.FULL_ACCESS)
                .enabled(true)
                .build());

        mockMvc.perform(patch("/api/users/{id}/block", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/users/{id}/password", user.getId())
                        .with(fullAccessUser())
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"replacement-password\"}"))
                .andExpect(status().isNotFound());

        var fullAccessUser = userRepository.findByUsername(username).orElseThrow();
        assertThat(fullAccessUser.isEnabled()).isTrue();
        assertThat(passwordHashingService.matches("password", fullAccessUser.getPasswordHash())).isTrue();
    }

    private CsrfBootstrap csrf() throws Exception {
        var response = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        var body = response.getResponse().getContentAsString();
        return new CsrfBootstrap(
                (MockHttpSession) response.getRequest().getSession(false),
                JsonPath.read(body, "$.headerName"),
                JsonPath.read(body, "$.token"));
    }

    private SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor fullAccessUser() {
        return SecurityMockMvcRequestPostProcessors.user("administrator").roles("FULL_ACCESS");
    }

    private record CsrfBootstrap(MockHttpSession session, String headerName, String token) {
    }
}
