package kz.vainshtein.raftscore.auth;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import testsupport.PostgreSqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserRepositoryIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAHashedPasswordAndUserAccessState() {
        var user = userRepository.saveAndFlush(
                User.builder()
                        .username("readonly")
                        .passwordHash("$argon2id$v=19$test-hash")
                        .role(UserRole.READ_ONLY)
                        .enabled(true)
                        .build());
        entityManager.clear();

        var persistedUser = userRepository.findByUsername("readonly");

        assertThat(persistedUser)
                .hasValueSatisfying(foundUser -> {
                    assertThat(foundUser.getId()).isEqualTo(user.getId());
                    assertThat(foundUser.getPasswordHash()).isEqualTo("$argon2id$v=19$test-hash");
                    assertThat(foundUser.getRole()).isEqualTo(UserRole.READ_ONLY);
                    assertThat(foundUser.isEnabled()).isTrue();
                    assertThat(foundUser.getVersion()).isZero();
                });
    }

    @Test
    @Transactional
    void incrementsVersionWhenUserAccessStateChanges() {
        var user = userRepository.saveAndFlush(
                User.builder()
                        .username("versioned")
                        .passwordHash("$argon2id$v=19$versioned-hash")
                        .role(UserRole.READ_ONLY)
                        .enabled(true)
                        .build());

        user.setEnabled(false);
        entityManager.flush();
        entityManager.clear();

        var updatedUser = userRepository.findById(user.getId());

        assertThat(updatedUser)
                .hasValueSatisfying(foundUser -> {
                    assertThat(foundUser.isEnabled()).isFalse();
                    assertThat(foundUser.getVersion()).isOne();
                });
    }
}
