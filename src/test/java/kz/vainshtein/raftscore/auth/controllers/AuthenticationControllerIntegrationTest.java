package kz.vainshtein.raftscore.auth.controllers;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(AuthenticationControllerIntegrationTest.AuthorizationPolicyProbeConfiguration.class)
class AuthenticationControllerIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void authenticatesFormLoginIntoAServerSessionThatExposesThePrincipal() throws Exception {
        var csrf = csrf();
        var login = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "integration-test-initial-password"))
                .andExpect(status().isNoContent())
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/auth/principal").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("FULL_ACCESS"));
    }

    @Test
    void authenticatesJsonLogin() throws Exception {
        var csrf = csrf();
        var login = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"integration-test-initial-password\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/auth/principal").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void rejectsUnauthenticatedPrincipalRequestsAndCsrfFreeLogins() throws Exception {
        mockMvc.perform(get("/api/auth/principal"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "integration-test-initial-password"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidCredentialsWithoutCreatingAnAuthenticatedSession() throws Exception {
        var csrf = csrf();
        var login = mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "incorrect-password"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        mockMvc.perform(get("/api/auth/principal").session((MockHttpSession) login.getRequest().getSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesStaticFrontendResourcesAndBootstrapsCsrfForAnonymousSessions() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        var csrf = csrf();

        mockMvc.perform(post("/api/auth/login")
                        .session(csrf.session())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "admin")
                        .param("password", "integration-test-initial-password"))
                .andExpect(status().isNoContent());
    }

    @Test
    void allowsAuthenticatedUsersToReadApiResourcesButOnlyFullAccessUsersToMutateThem() throws Exception {
        mockMvc.perform(get("/api/authorization-policy-probe")
                        .with(SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/authorization-policy-probe")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user("reader").roles("READ_ONLY")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/authorization-policy-probe")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user("administrator").roles("FULL_ACCESS")))
                .andExpect(status().isOk());
    }

    private CsrfBootstrap csrf() throws Exception {
        var response = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        var body = response.getResponse().getContentAsString();
        return new CsrfBootstrap(
                (MockHttpSession) response.getRequest().getSession(false),
                JsonPath.read(body, "$.headerName"),
                JsonPath.read(body, "$.token"));
    }

    private record CsrfBootstrap(MockHttpSession session, String headerName, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthorizationPolicyProbeConfiguration {

        @Bean
        AuthorizationPolicyProbeController authorizationPolicyProbeController() {
            return new AuthorizationPolicyProbeController();
        }
    }

    @RestController
    @RequestMapping("/api/authorization-policy-probe")
    static class AuthorizationPolicyProbeController {

        @GetMapping
        String read() {
            return "read";
        }

        @PostMapping
        void write() {
        }
    }
}
