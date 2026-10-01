package kz.vainshtein.raftscore.auth.controllers;

import com.jayway.jsonpath.JsonPath;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
}
