package kz.vainshtein.raftscore.auth.services;

import jakarta.persistence.EntityManager;
import kz.vainshtein.raftscore.RaftScoreProperties;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Creates the initial full-access account from server configuration when none exists. */
@Component
@RequiredArgsConstructor
class InitialAdministratorBootstrap implements ApplicationRunner {

    private static final long INITIAL_ADMINISTRATOR_LOCK_ID = 0x5241465453434F52L;

    private final UserRepository userRepository;
    private final PasswordHashingService passwordHashingService;
    private final RaftScoreProperties properties;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        acquireBootstrapLock();
        if (userRepository.existsByRole(UserRole.FULL_ACCESS)) {
            return;
        }

        var administrator = properties.initialAdministrator();
        if (!StringUtils.hasText(administrator.username())) {
            throw new IllegalStateException(
                    "RAFT_SCORE_INITIAL_ADMINISTRATOR_USERNAME must be set before creating the initial administrator.");
        }
        if (!StringUtils.hasText(administrator.password())) {
            throw new IllegalStateException(
                    "RAFT_SCORE_INITIAL_ADMINISTRATOR_PASSWORD must be set before creating the initial administrator.");
        }

        userRepository.save(User.builder()
                .username(administrator.username())
                .passwordHash(passwordHashingService.hash(administrator.password()))
                .role(UserRole.FULL_ACCESS)
                .enabled(true)
                .build());
    }

    private void acquireBootstrapLock() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
                .setParameter("lockId", INITIAL_ADMINISTRATOR_LOCK_ID)
                .getSingleResult();
    }
}
