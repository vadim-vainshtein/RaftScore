package kz.vainshtein.raftscore.auth;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import kz.vainshtein.raftscore.RaftScoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialAdministratorBootstrapTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordHashingService passwordHashingService = mock(PasswordHashingService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query bootstrapLockQuery = mock(Query.class);

    @BeforeEach
    void acquireBootstrapLock() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(bootstrapLockQuery);
        when(bootstrapLockQuery.setParameter(anyString(), anyLong())).thenReturn(bootstrapLockQuery);
    }

    @Test
    void createsAnEnabledFullAccessUserFromTheInitialServerConfiguration() throws Exception {
        when(passwordHashingService.hash("initial-secret")).thenReturn("password-hash");
        var bootstrap = bootstrap("chief-judge", "initial-secret");

        bootstrap.run(new DefaultApplicationArguments());

        var userCaptor = forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        var createdUser = userCaptor.getValue();
        assertThat(createdUser.getUsername()).isEqualTo("chief-judge");
        assertThat(createdUser.getPasswordHash()).isEqualTo("password-hash");
        assertThat(createdUser.getRole()).isEqualTo(UserRole.FULL_ACCESS);
        assertThat(createdUser.isEnabled()).isTrue();
    }

    @Test
    void leavesAnExistingFullAccessUserUnchanged() throws Exception {
        when(userRepository.existsByRole(UserRole.FULL_ACCESS)).thenReturn(true);
        var bootstrap = bootstrap("chief-judge", "replacement-secret");

        bootstrap.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        verify(passwordHashingService, never()).hash(any());
    }

    @Test
    void rejectsMissingInitialAdministratorPassword() {
        var bootstrap = bootstrap("chief-judge", " ");

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .withMessageContaining("RAFT_SCORE_INITIAL_ADMINISTRATOR_PASSWORD");

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsBlankInitialAdministratorUsername() {
        var bootstrap = bootstrap(" ", "initial-secret");

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .withMessageContaining("RAFT_SCORE_INITIAL_ADMINISTRATOR_USERNAME");

        verify(userRepository, never()).save(any());
    }

    private InitialAdministratorBootstrap bootstrap(String username, String password) {
        var properties = new RaftScoreProperties(new RaftScoreProperties.InitialAdministrator(username, password));
        return new InitialAdministratorBootstrap(userRepository, passwordHashingService, properties, entityManager);
    }
}
