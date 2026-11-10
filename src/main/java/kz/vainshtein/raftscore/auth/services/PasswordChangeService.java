package kz.vainshtein.raftscore.auth.services;

import java.nio.charset.StandardCharsets;

import kz.vainshtein.raftscore.auth.entities.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Changes the password of the authenticated account. */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private final UserRepository users;
    private final PasswordHashingService passwords;

    @Transactional
    public boolean change(String username, String currentPassword, String newPassword) {
        if (invalid(currentPassword) || invalid(newPassword)) {
            throw new IllegalArgumentException("Invalid password");
        }
        var user = users.findByUsername(username).orElse(null);
        if (user == null || !user.isEnabled() || !passwords.matches(currentPassword, user.getPasswordHash())) {
            return false;
        }
        user.resetPassword(passwords.hash(newPassword));
        return true;
    }
    private boolean invalid(String password) {
        return password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72;
    }
}
