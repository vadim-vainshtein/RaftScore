package testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL Testcontainers configuration for Spring integration tests.
 *
 * <p>The container is owned by the test JVM rather than the JUnit lifecycle. Spring caches application
 * contexts across test classes, so their data sources must outlive each individual test class.</p>
 */
public abstract class PostgreSqlIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRESQL.start();
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("raftscore.initial-administrator.password", () -> "integration-test-initial-password");
    }
}
