package dev.ragplatform.infrastructure.web.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;

/**
 * Rate limiter por usuário autenticado usando Redis como contador de janela deslizante.
 *
 * Chave: "rate:chat:{userId}:{janela_de_minuto}"
 * Janela: 1 minuto (floor do timestamp em ms / 60.000)
 * Limite: configurável via app.ratelimit.chat.max-per-minute
 *
 * Resposta ao exceder: 429 Too Many Requests com ProblemDetail JSON.
 * Usuários não autenticados passam direto (auth é verificada pela cadeia do Spring Security).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final StringRedisTemplate redis;
    private final int maxPerMinute;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${app.ratelimit.chat.max-per-minute:20}") int maxPerMinute) {
        this.redis = redis;
        this.maxPerMinute = maxPerMinute;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return true; // autenticação é resolvida pelo Spring Security depois
        }

        String userId = auth.getName();
        long window   = System.currentTimeMillis() / 60_000L;
        String key    = "rate:chat:" + userId + ":" + window;

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // TTL de 2 minutos para limpar a chave mesmo se a janela mudar
            redis.expire(key, Duration.ofMinutes(2));
        }

        if (count != null && count > maxPerMinute) {
            log.warn("Rate limit atingido: userId={} count={} limite={}", userId, count, maxPerMinute);
            response.setStatus(429);
            response.setContentType("application/problem+json;charset=UTF-8");
            response.getWriter().write("""
                    {
                      "type": "https://ragplatform.dev/errors/rate-limit",
                      "title": "Limite de requisições atingido",
                      "status": 429,
                      "detail": "Você atingiu o limite de %d requisições por minuto. Aguarde e tente novamente."
                    }
                    """.formatted(maxPerMinute));
            return false;
        }

        return true;
    }
}
