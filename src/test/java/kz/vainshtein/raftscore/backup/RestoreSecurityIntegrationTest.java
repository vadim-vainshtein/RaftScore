package kz.vainshtein.raftscore.backup;

import java.io.IOException;
import java.util.UUID;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RestoreSecurityIntegrationTest extends PostgreSqlIntegrationTest {
    private static final String PASSWORD = "integration-test-initial-password";
    private static final String BODY = "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"confirmed\":true,\"password\":\"" + PASSWORD + "\"}";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoBean RestoreService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (users.findByUsername("restore-reader").isEmpty()) {
            users.save(User.builder().username("restore-reader").passwordHash(encoder.encode(PASSWORD))
                    .role(UserRole.READ_ONLY).enabled(true).build());
        }
    }

    @Test
    void restoreRequiresAccessAndCsrfAndInvalidatesSessionsOnlyOnSuccess() throws Exception {
        var first = login("admin");
        var second = login("admin");
        var reader = login("restore-reader");
        mvc.perform(post("/api/backups/restore").session(first).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/backups/restore").session(reader).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        doThrow(new IOException("failure")).doNothing().when(service).restore(any(UUID.class));
        mvc.perform(post("/api/backups/restore").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/principal").session(first)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/principal").session(second)).andExpect(status().isOk());
        mvc.perform(post("/api/backups/restore").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/principal").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/principal").session(second)).andExpect(status().isUnauthorized());
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
