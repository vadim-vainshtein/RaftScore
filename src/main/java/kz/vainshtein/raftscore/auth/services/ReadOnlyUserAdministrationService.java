package kz.vainshtein.raftscore.auth.services;

import java.nio.charset.StandardCharsets;
import kz.vainshtein.raftscore.auth.entities.User;
import kz.vainshtein.raftscore.auth.entities.UserRepository;
import kz.vainshtein.raftscore.auth.models.CreateReadOnlyUserRequest;
import kz.vainshtein.raftscore.auth.models.ResetPasswordRequest;
import kz.vainshtein.raftscore.auth.models.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates and manages credentials for read-only users. */
@Service
@RequiredArgsConstructor
public class ReadOnlyUserAdministrationService {

    private final UserRepository userRepository;
    private final PasswordHashingService passwordHashingService;

    @Transactional
    public Long create(CreateReadOnlyUserRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already in use");
        }

        try {
            return userRepository.saveAndFlush(User.builder()
                    .username(request.username())
                    .passwordHash(hashPassword(request.password()))
                    .role(UserRole.READ_ONLY)
                    .enabled(true)
                    .build()).getId();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already in use", exception);
        }
    }

    @Transactional
    public void block(Long id) {
        readOnlyUser(id).setEnabled(false);
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest request) {
        readOnlyUser(id).resetPassword(hashPassword(request.password()));
    }

    private String hashPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
        return passwordHashingService.hash(password);
    }
    private User readOnlyUser(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.getRole() == UserRole.READ_ONLY)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Read-only user not found"));
    }
}
