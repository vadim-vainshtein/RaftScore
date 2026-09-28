package kz.vainshtein.raftscore.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashingServiceTest {

    private final PasswordHashingConfiguration configuration = new PasswordHashingConfiguration();
    private final PasswordHashingService passwordHashingService =
            new PasswordHashingService(configuration.passwordEncoder());

    @Test
    void hashesPasswordsAndVerifiesTheOriginalPasswordOnly() {
        var passwordHash = passwordHashingService.hash("correct horse battery staple");

        assertThat(passwordHash)
                .isNotEqualTo("correct horse battery staple")
                .startsWith("$2a$12$");
        assertThat(passwordHashingService.matches("correct horse battery staple", passwordHash)).isTrue();
        assertThat(passwordHashingService.matches("incorrect password", passwordHash)).isFalse();
    }
}
