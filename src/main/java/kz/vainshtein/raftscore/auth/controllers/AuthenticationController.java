package kz.vainshtein.raftscore.auth.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kz.vainshtein.raftscore.auth.models.AuthenticatedPrincipal;
import kz.vainshtein.raftscore.auth.models.CsrfTokenResponse;
import kz.vainshtein.raftscore.auth.models.LoginRequest;
import kz.vainshtein.raftscore.auth.services.AuthenticationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for authenticating and identifying the current user. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthenticationController {

    private final AuthenticationService authenticationService;

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> loginForm(
            @RequestParam String username,
            @RequestParam String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        return login(username, password, request, response);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> loginJson(
            @RequestBody LoginRequest login,
            HttpServletRequest request,
            HttpServletResponse response) {
        return login(login.username(), login.password(), request, response);
    }

    @GetMapping("/principal")
    AuthenticatedPrincipal principal(Authentication authentication) {
        return authenticationService.principalFor(authentication);
    }

    @GetMapping("/csrf")
    CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return new CsrfTokenResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken());
    }

    private ResponseEntity<Void> login(
            String username,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        return authenticationService.authenticate(username, password, request, response)
                ? ResponseEntity.status(HttpStatus.NO_CONTENT).build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
