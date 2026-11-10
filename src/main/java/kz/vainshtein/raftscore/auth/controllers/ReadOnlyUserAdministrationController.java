package kz.vainshtein.raftscore.auth.controllers;

import jakarta.validation.Valid;
import java.net.URI;
import kz.vainshtein.raftscore.auth.models.CreateReadOnlyUserRequest;
import kz.vainshtein.raftscore.auth.models.ResetPasswordRequest;
import kz.vainshtein.raftscore.auth.services.ReadOnlyUserAdministrationService;
import lombok.RequiredArgsConstructor;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for full-access administration of read-only users. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
class ReadOnlyUserAdministrationController {

    private final ReadOnlyUserAdministrationService userAdministrationService;

    @PostMapping
    ResponseEntity<Void> create(@Valid @RequestBody CreateReadOnlyUserRequest request) {
        var id = userAdministrationService.create(request);
        return ResponseEntity.created(URI.create("/api/users/" + id)).build();
    }

    @PatchMapping("/{id}/block")
    ResponseEntity<Void> block(@PathVariable Long id) {
        userAdministrationService.block(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userAdministrationService.resetPassword(id, request);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Void> concurrentUpdate() {
        return ResponseEntity.status(409).build();
    }
}
