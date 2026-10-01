package kz.vainshtein.raftscore;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import kz.vainshtein.raftscore.auth.services.PasswordHashingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RaftScoreApplicationTests extends PostgreSqlIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHashingService passwordHashingService;

    @Test
    void appliesLiquibaseMigrationsToPostgreSql() {
        var baselineChangeSetCount = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM databasechangelog
                WHERE id = '0001-baseline'
                """).getSingleResult();

        assertThat(baselineChangeSetCount.intValue()).isOne();
    }

    @Test
    void bootstrapsTheInitialFullAccessUserFromServerConfiguration() {
        assertThat(userRepository.findByUsername("admin"))
                .hasValueSatisfying(administrator -> {
                    assertThat(administrator.getRole()).isEqualTo(UserRole.FULL_ACCESS);
                    assertThat(administrator.isEnabled()).isTrue();
                    assertThat(passwordHashingService.matches(
                            "integration-test-initial-password", administrator.getPasswordHash())).isTrue();
                });
    }
}
