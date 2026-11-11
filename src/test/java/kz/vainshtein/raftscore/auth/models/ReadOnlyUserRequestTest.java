package kz.vainshtein.raftscore.auth.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyUserRequestTest {
    @Test
    void requestStringRepresentationsDoNotExposePasswords() {
        var password = "sensitive-password-123";
        assertThat(new CreateReadOnlyUserRequest("reader", password).toString()).doesNotContain(password);
        assertThat(new ResetPasswordRequest(password).toString()).doesNotContain(password);
    }
}
