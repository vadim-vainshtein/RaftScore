package kz.vainshtein.raftscore.auth.configurations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookieConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ServerPropertiesConfiguration.class));

    @Test
    void configuresTheDefaultSessionCookiePolicy() {
        contextRunner.run(context -> {
            var cookie = sessionCookie(context);

            assertThat(cookie.getName()).isEqualTo("RAFTSCORE_SESSION");
            assertThat(cookie.getHttpOnly()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
            assertThat(cookie.getSecure()).isFalse();
        });
    }

    @Test
    void allowsSecureSessionCookiesToBeEnabledPerEnvironment() {
        contextRunner.withPropertyValues("RAFT_SCORE_COOKIE_SECURE=true")
                .run(context -> assertThat(sessionCookie(context).getSecure()).isTrue());
    }

    private Cookie sessionCookie(org.springframework.context.ApplicationContext context) {
        return context.getBean(ServerProperties.class)
                .getServlet()
                .getSession()
                .getCookie();
    }

    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerPropertiesConfiguration {
    }
}
