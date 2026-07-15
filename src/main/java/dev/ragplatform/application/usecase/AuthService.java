package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.exception.EmailAlreadyTakenException;
import dev.ragplatform.infrastructure.persistence.user.UserJpaEntity;
import dev.ragplatform.infrastructure.persistence.user.UserJpaRepository;
import dev.ragplatform.infrastructure.security.JwtService;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.auth.LoginRequest;
import dev.ragplatform.infrastructure.web.auth.RegisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserJpaRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyTakenException(request.email());
        }

        var entity = new UserJpaEntity(
                request.name(),
                request.email(),
                passwordEncoder.encode(request.password())
        );
        userRepository.save(entity);

        String token = jwtService.generate(entity.getId(), entity.getEmail(), entity.getRole().name());
        return toResponse(entity, token);
    }

    public AuthResponse login(LoginRequest request) {
        // Delega validação de credenciais ao AuthenticationManager (usa BCrypt internamente)
        var auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        String token = jwtService.generate(principal.getId(), principal.getEmail(),
                principal.getAuthorities().iterator().next().getAuthority().replace("ROLE_", ""));
        return toResponse(principal, token);
    }

    private AuthResponse toResponse(UserJpaEntity entity, String token) {
        return new AuthResponse(token,
                new AuthResponse.UserInfo(entity.getId(), entity.getName(), entity.getEmail()));
    }

    private AuthResponse toResponse(UserPrincipal principal, String token) {
        return new AuthResponse(token,
                new AuthResponse.UserInfo(principal.getId(), principal.getName(), principal.getEmail()));
    }
}
