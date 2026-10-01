package kz.vainshtein.raftscore.auth.models;

/** Credentials submitted to the JSON login endpoint. */
public record LoginRequest(String username, String password) {
}
