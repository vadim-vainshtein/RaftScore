package kz.vainshtein.raftscore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    @EnableConfigurationProperties(RaftScoreProperties.class)
    static class PropertiesConfiguration {
    }
}
