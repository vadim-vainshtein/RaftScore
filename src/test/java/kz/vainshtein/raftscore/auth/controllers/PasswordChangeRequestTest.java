package kz.vainshtein.raftscore.auth.controllers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordChangeRequestTest {

    @Test
    void stringRepresentationDoesNotExposePasswords() {
        var request = new PasswordChangeController.PasswordChangeRequest("old-secret", "new-secret");

        assertThat(request.toString()).doesNotContain("old-secret", "new-secret");
    }
}
