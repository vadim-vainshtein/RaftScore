package kz.vainshtein.raftscore.auth.models;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Credentials for creating a read-only user. */
public record CreateReadOnlyUserRequest(@NotBlank @Pattern(regexp = "[^\\x00]*") String username, @NotBlank String password) {
    @Override
    public String toString() {
        return "CreateReadOnlyUserRequest[redacted]";
    }
}
