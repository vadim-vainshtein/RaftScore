package kz.vainshtein.raftscore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RaftScorePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertiesConfiguration.class));

    @Test
    void usesTheDefaultInitialAdministratorUsername() {
        contextRunner.run(context -> {
            RaftScoreProperties properties = context.getBean(RaftScoreProperties.class);

            assertThat(properties.initialAdministrator().username()).isEqualTo("admin");
        });
    }

    @Test
    void bindsEnvironmentSpecificSettings() {
        contextRunner.withPropertyValues(
                "raftscore.initial-administrator.username=chief-judge",
                "raftscore.initial-administrator.password=admin-secret")
                .run(context -> {
                    RaftScoreProperties properties = context.getBean(RaftScoreProperties.class);

                    assertThat(properties.initialAdministrator().username()).isEqualTo("chief-judge");
                    assertThat(properties.initialAdministrator().password()).isEqualTo("admin-secret");
                });
    }

    @Test
    void definesLocalNetworkDeploymentDefaults() throws IOException {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .getFirst();

        assertThat(properties.getProperty("spring.datasource.url"))
                .isEqualTo("${RAFT_SCORE_DATABASE_URL:jdbc:postgresql://localhost:5432/raftscore}");
        assertThat(properties.getProperty("server.address")).isEqualTo("${RAFT_SCORE_SERVER_ADDRESS:0.0.0.0}");
        assertThat(properties.getProperty("server.servlet.session.cookie.http-only"))
                .isEqualTo("${RAFT_SCORE_COOKIE_HTTP_ONLY:true}");
        assertThat(properties.getProperty("server.servlet.session.cookie.secure"))
                .isEqualTo("${RAFT_SCORE_COOKIE_SECURE:false}");
        assertThat(properties.getProperty("server.servlet.session.cookie.same-site"))
                .isEqualTo("${RAFT_SCORE_COOKIE_SAME_SITE:Lax}");
    }

    @EnableConfigurationProperties(RaftScoreProperties.class)
    static class PropertiesConfiguration {
    }
}
