package dev.ragplatform.infrastructure.web.auth;

import java.util.UUID;

public record AuthResponse(String token, UserInfo user) {

    public record UserInfo(UUID id, String name, String email) {}
}
