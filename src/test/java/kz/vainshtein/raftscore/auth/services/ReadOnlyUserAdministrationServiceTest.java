package kz.vainshtein.raftscore.auth.services;

import java.util.Optional;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.CreateReadOnlyUserRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadOnlyUserAdministrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHashingService passwordHashingService;

    @Test
    void translatesAConcurrentDuplicateUsernameConstraintViolationToConflict() {
        when(userRepository.findByUsername("duplicate")).thenReturn(Optional.empty());
        when(passwordHashingService.hash("password")).thenReturn("password-hash");
        doThrow(new DataIntegrityViolationException("duplicate username"))
                .when(userRepository).saveAndFlush(any(User.class));
        var service = new ReadOnlyUserAdministrationService(userRepository, passwordHashingService);

        assertThatThrownBy(() -> service.create(new CreateReadOnlyUserRequest("duplicate", "password")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
