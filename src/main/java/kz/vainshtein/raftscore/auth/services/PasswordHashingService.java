package kz.vainshtein.raftscore.auth.services;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Hashes and verifies user passwords without exposing encoder details to callers. */
@Service
public class PasswordHashingService {

    private final PasswordEncoder passwordEncoder;

    public PasswordHashingService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String hash(String password) {
        return passwordEncoder.encode(password);
    }

    public boolean matches(String password, String passwordHash) {
        return passwordEncoder.matches(password, passwordHash);
    }
}
