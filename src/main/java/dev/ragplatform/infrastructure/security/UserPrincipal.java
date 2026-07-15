package dev.ragplatform.infrastructure.security;

import dev.ragplatform.infrastructure.persistence.user.UserJpaEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Adapta UserJpaEntity ao contrato UserDetails do Spring Security.
 * Carregado pelo CustomUserDetailsService e populado no SecurityContext
 * pelo JwtAuthenticationFilter.
 */
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String name;
    private final String email;
    private final String passwordHash;
    private final List<GrantedAuthority> authorities;

    public UserPrincipal(UserJpaEntity entity) {
        this.id = entity.getId();
        this.name = entity.getName();
        this.email = entity.getEmail();
        this.passwordHash = entity.getPasswordHash();
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + entity.getRole().name()));
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }

    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
