package kz.vainshtein.raftscore.backup;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import kz.vainshtein.raftscore.api.ApiError;
import kz.vainshtein.raftscore.auth.configurations.RestoreGateFilter;
import kz.vainshtein.raftscore.auth.configurations.SessionEpoch;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/backups/restore")
@RequiredArgsConstructor
class RestoreController {
    private static final Logger log = LoggerFactory.getLogger(RestoreController.class);
    private final RestoreService service;
    private final AuthenticationManager authenticationManager;
    private final SessionEpoch sessionEpoch;

    record RestoreRequest(UUID id, boolean confirmed, String password) {
        @Override public String toString() { return "RestoreRequest[id=" + id + ", confirmed=" + confirmed + ", password=[REDACTED]]"; }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> local(@RequestBody RestoreRequest body, Principal principal, HttpServletRequest request) {
        if (body.id() == null) return error(400, "request.malformed");
        return restore(body.confirmed(), body.password(), principal, request, () -> service.restore(body.id()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(@RequestParam boolean confirmed, @RequestParam String password,
                             @RequestPart("file") MultipartFile file, Principal principal, HttpServletRequest request) {
        return restore(confirmed, password, principal, request, () -> {
            try (var input = file.getInputStream()) { service.restoreUpload(input); }
        });
    }

    private ResponseEntity<?> restore(boolean confirmed, String password, Principal principal,
                                      HttpServletRequest request, RestoreAction action) {
        if (!confirmed) return error(400, "backup.confirmation_required");
        if (!hasFullAccess(principal, password)) return error(403, "backup.reauthentication_failed");
        var epoch = sessionEpoch.current();
        if (!RestoreGateFilter.tryPromote(request)) return error(503, "backup.restore_busy");
        if (epoch != sessionEpoch.current() || !hasFullAccess(principal, password)) {
            return error(403, "backup.reauthentication_failed");
        }
        try {
            action.run();
            sessionEpoch.invalidateAll();
            log.info("Backup restore succeeded");
            return ResponseEntity.noContent().build();
        } catch (IOException | RuntimeException ex) {
            log.warn("Backup restore failed");
            return error(400, "backup.restore_failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Backup restore interrupted");
            return error(503, "backup.restore_failed");
        }
    }

    private boolean hasFullAccess(Principal principal, String password) {
        try {
            var authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(principal.getName(), password));
            return authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_FULL_ACCESS".equals(authority.getAuthority()));
        } catch (AuthenticationException | IllegalArgumentException ex) {
            log.warn("Backup restore reauthentication failed");
            return false;
        }
    }
    private ResponseEntity<ApiError> error(int status, String code) {
        return ResponseEntity.status(status).body(ApiError.of(code));
    }

    @FunctionalInterface private interface RestoreAction { void run() throws IOException, InterruptedException; }
}
