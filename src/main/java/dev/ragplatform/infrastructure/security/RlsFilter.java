package dev.ragplatform.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Popula o RlsContext com o userId do usuário autenticado antes de cada requisição.
 *
 * Executa após o JwtAuthenticationFilter (Spring Security roda a -100; este filtro
 * fica sem @Order = Integer.MAX_VALUE, portanto após a cadeia de segurança).
 * Quando o usuário não está autenticado, o contexto permanece null e o RLS permite
 * todas as linhas (path público — o Spring Security rejeita /api/** antes do banco).
 */
@Component
public class RlsFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                RlsContext.set(principal.getId().toString());
            }
            chain.doFilter(request, response);
        } finally {
            RlsContext.clear();
        }
    }
}
