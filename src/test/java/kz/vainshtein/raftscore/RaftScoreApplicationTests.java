package kz.vainshtein.raftscore;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RaftScoreApplicationTests extends PostgreSqlIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void appliesLiquibaseMigrationsToPostgreSql() {
        var baselineChangeSetCount = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM databasechangelog
                WHERE id = '0001-baseline'
                """).getSingleResult();

        assertThat(baselineChangeSetCount.intValue()).isOne();
    }
}
