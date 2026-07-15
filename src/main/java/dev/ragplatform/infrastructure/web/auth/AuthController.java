package dev.ragplatform.infrastructure.web.auth;

import dev.ragplatform.application.usecase.AuthService;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/auth/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/auth/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Rota protegida que retorna dados do usuário autenticado. Útil para o frontend validar o token. */
    @GetMapping("/api/me")
    ResponseEntity<AuthResponse.UserInfo> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                new AuthResponse.UserInfo(principal.getId(), principal.getName(), principal.getEmail()));
    }
}
