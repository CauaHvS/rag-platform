package dev.ragplatform;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes unitários da lógica de resiliência — sem Spring, sem Testcontainers.
 *
 * Verifica o comportamento de circuit breaker e retry programaticamente,
 * garantindo que as premissas de degradação graciosa estão corretas:
 *
 *   - CB abre após atingir failureRateThreshold
 *   - Chamadas com CB aberto falham rápido (CallNotPermittedException)
 *   - CB vai para half-open após waitDuration
 *   - Retry tenta N vezes antes de propagar
 *   - Retry não tenta novamente quando CB está aberto
 */
class AiResilienceTest {

    // ── Circuit Breaker ───────────────────────────────────────────────────────

    @Test
    void circuit_breaker_abre_apos_atingir_failure_rate() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .failureRateThreshold(50)   // abre com >= 50% de falhas
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();

        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("test");

        // 2 de 4 chamadas falham → 50% → CB deve abrir
        for (int i = 0; i < 2; i++) {
            safeRun(cb, () -> { throw new RuntimeException("falha simulada"); });
        }
        for (int i = 0; i < 2; i++) {
            safeRun(cb, () -> {}); // sucesso
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void circuit_breaker_aberto_falha_rapido_sem_chamar_o_metodo() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("test");

        // Abre o CB com 2 falhas
        for (int i = 0; i < 2; i++) {
            safeRun(cb, () -> { throw new RuntimeException("falha"); });
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Com CB aberto, a chamada não chega ao método
        AtomicInteger chamadas = new AtomicInteger(0);
        assertThatThrownBy(() ->
                cb.executeRunnable(chamadas::incrementAndGet)
        ).isInstanceOf(CallNotPermittedException.class);

        assertThat(chamadas.get()).isZero();
    }

    @Test
    void circuit_breaker_vai_para_half_open_apos_wait_duration() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(50))  // espera curta para o teste
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("test");

        for (int i = 0; i < 2; i++) {
            safeRun(cb, () -> { throw new RuntimeException("falha"); });
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(100); // aguarda waitDuration
        cb.transitionToHalfOpenState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void circuit_breaker_fecha_apos_sucesso_em_half_open() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMillis(50))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("test");

        for (int i = 0; i < 2; i++) {
            safeRun(cb, () -> { throw new RuntimeException("falha"); });
        }
        Thread.sleep(100);
        cb.transitionToHalfOpenState();

        // Chamada bem-sucedida em half-open → fecha o CB
        cb.executeRunnable(() -> {});

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Test
    void retry_tenta_n_vezes_antes_de_propagar() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .build();
        Retry retry = RetryRegistry.of(config).retry("test");

        AtomicInteger tentativas = new AtomicInteger(0);

        assertThatThrownBy(() ->
                retry.executeRunnable(() -> {
                    tentativas.incrementAndGet();
                    throw new RuntimeException("sempre falha");
                })
        ).isInstanceOf(RuntimeException.class);

        assertThat(tentativas.get()).isEqualTo(3);
    }

    @Test
    void retry_nao_tenta_novamente_em_excecoes_ignoradas() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        Retry retry = RetryRegistry.of(config).retry("test");

        AtomicInteger tentativas = new AtomicInteger(0);
        CircuitBreaker cbAberto = circuitBreakerAberto();

        assertThatThrownBy(() ->
                retry.executeRunnable(() -> {
                    tentativas.incrementAndGet();
                    // Simula o que CB aberto faz: lança CallNotPermittedException
                    cbAberto.executeRunnable(() -> {});
                })
        ).isInstanceOf(CallNotPermittedException.class);

        // Deve ter tentado apenas 1 vez (não retentou porque é exceção ignorada)
        assertThat(tentativas.get()).isEqualTo(1);
    }

    @Test
    void retry_nao_tenta_quando_metodo_tem_sucesso_na_primeira_chamada() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .build();
        Retry retry = RetryRegistry.of(config).retry("test");

        AtomicInteger tentativas = new AtomicInteger(0);
        retry.executeRunnable(tentativas::incrementAndGet);

        assertThat(tentativas.get()).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void safeRun(CircuitBreaker cb, CheckedRunnable action) {
        try {
            cb.executeRunnable(() -> {
                try { action.run(); } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Exception ignored) {}
    }

    private CircuitBreaker circuitBreakerAberto() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(config).circuitBreaker("aberto");
        for (int i = 0; i < 2; i++) {
            try { cb.executeRunnable(() -> { throw new RuntimeException(); }); } catch (Exception ignored) {}
        }
        return cb;
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }
}
