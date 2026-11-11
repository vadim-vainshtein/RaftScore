package kz.vainshtein.raftscore.auth.models;

import jakarta.validation.constraints.NotBlank;

/** Replacement credential for an existing user. */
public record ResetPasswordRequest(@NotBlank String password) {
    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
