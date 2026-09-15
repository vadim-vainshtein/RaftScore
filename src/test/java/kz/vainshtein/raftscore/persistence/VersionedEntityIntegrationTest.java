package kz.vainshtein.raftscore.persistence;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import testsupport.PostgreSqlIntegrationTest;
import testsupport.persistence.OptimisticLockingTestEntity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = VersionedEntityIntegrationTest.TestApplication.class,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class VersionedEntityIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void rejectsAnUpdateFromAStalePersistenceContext() {
        var id = persistProbeEntity();

        try (var firstContext = entityManagerFactory.createEntityManager();
             var staleContext = entityManagerFactory.createEntityManager()) {
            firstContext.getTransaction().begin();
            staleContext.getTransaction().begin();

            var current = firstContext.find(OptimisticLockingTestEntity.class, id);
            var stale = staleContext.find(OptimisticLockingTestEntity.class, id);

            current.setValue("current update");
            firstContext.getTransaction().commit();

            stale.setValue("stale update");
            assertThatThrownBy(staleContext::flush)
                    .isInstanceOf(OptimisticLockException.class);

            staleContext.getTransaction().rollback();
        }
    }

    private Long persistProbeEntity() {
        try (var entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            var entity = new OptimisticLockingTestEntity("initial value");
            entityManager.persist(entity);
            entityManager.getTransaction().commit();
            return entity.getId();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OptimisticLockingTestEntity.class)
    static class TestApplication {
    }
}
