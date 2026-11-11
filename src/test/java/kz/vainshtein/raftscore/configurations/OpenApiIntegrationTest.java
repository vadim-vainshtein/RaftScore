package kz.vainshtein.raftscore.configurations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import testsupport.PostgreSqlIntegrationTest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class OpenApiIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void documentationAndAssetsRequireAuthentication() throws Exception {
        for (var path : new String[]{"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
                "/swagger-ui/index.html", "/swagger-ui/swagger-ui-bundle.js"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void authenticatedUserCanReadGeneratedContractAndLocalUi() throws Exception {
        mvc.perform(get("/v3/api-docs").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(containsString("3.")))
                .andExpect(jsonPath("$.info.title").value("RaftScore API"))
                .andExpect(jsonPath("$.paths['/api/auth/principal'].get").exists())
                .andExpect(jsonPath("$.paths['/error']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.requestBody").doesNotExist());
        mvc.perform(get("/v3/api-docs/swagger-config").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.supportedSubmitMethods", empty()));
        mvc.perform(get("/swagger-ui.html").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui-bundle.js")));
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js").with(user("reader").roles("READ_ONLY")))
                .andExpect(status().isOk());
    }
}
