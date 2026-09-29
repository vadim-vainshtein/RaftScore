package kz.vainshtein.raftscore.auth;

import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InitialAdministratorBootstrapIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    @Qualifier("initialAdministratorBootstrap")
    private ApplicationRunner bootstrap;

    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentBootstrapsCreateOnlyOneFullAccessUser() throws Exception {
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = executor.invokeAll(List.of(
                    () -> {
                        bootstrap.run(new DefaultApplicationArguments());
                        return null;
                    },
                    () -> {
                        bootstrap.run(new DefaultApplicationArguments());
                        return null;
                    }));

            for (var attempt : attempts) {
                attempt.get();
            }
        }

        assertThat(userRepository.findAll())
                .filteredOn(user -> user.getRole() == UserRole.FULL_ACCESS)
                .hasSize(1);
    }
}
