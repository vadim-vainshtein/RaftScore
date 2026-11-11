package kz.vainshtein.raftscore.auth.controllers;

import kz.vainshtein.raftscore.auth.services.PasswordChangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Allows a signed-in user to change their own password. */
@RestController
@RequiredArgsConstructor
class PasswordChangeController {

    private final PasswordChangeService passwordChangeService;

    @PutMapping("/api/auth/password")
    ResponseEntity<Void> changePassword(Authentication authentication, @RequestBody PasswordChangeRequest request) {
        try {
            return passwordChangeService.change(authentication.getName(), request.currentPassword(), request.newPassword())
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    record PasswordChangeRequest(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "PasswordChangeRequest[redacted]";
        }
    }
}
