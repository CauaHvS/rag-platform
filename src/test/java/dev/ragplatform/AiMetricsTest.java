package dev.ragplatform;

import dev.ragplatform.infrastructure.observability.AiMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Testes unitários de AiMetrics — sem Spring, sem Testcontainers.
 *
 * Usa SimpleMeterRegistry (in-memory) para verificar que os contadores
 * são registrados com os valores e tags corretos.
 */
class AiMetricsTest {

    private SimpleMeterRegistry registry;
    private AiMetrics aiMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        aiMetrics = new AiMetrics(registry);
    }

    // ── recordChatTokens ──────────────────────────────────────────────────────

    @Test
    void registra_tokens_prompt_e_completion_com_tags_corretas() {
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-123", 100, 50);

        double prompt = counter("ai.tokens", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-123", "type", "prompt");
        double completion = counter("ai.tokens", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-123", "type", "completion");

        assertThat(prompt).isEqualTo(100.0);
        assertThat(completion).isEqualTo(50.0);
    }

    @Test
    void registra_custo_usd_com_tags_corretas() {
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-123", 1_000_000, 1_000_000);

        double cost = counter("ai.cost.usd", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-123");

        // 1M input × $0,59 + 1M output × $0,79 = $1,38
        assertThat(cost).isCloseTo(1.38, within(0.001));
    }

    @Test
    void acumula_tokens_de_multiplas_chamadas_do_mesmo_usuario() {
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-abc", 200, 80);
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-abc", 300, 120);

        double prompt = counter("ai.tokens", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-abc", "type", "prompt");

        assertThat(prompt).isEqualTo(500.0);
    }

    @Test
    void usuarios_distintos_tem_contadores_independentes() {
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-A", 100, 40);
        aiMetrics.recordChatTokens("groq", "llama-3.3-70b-versatile", "user-B", 200, 80);

        double tokensA = counter("ai.tokens", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-A", "type", "prompt");
        double tokensB = counter("ai.tokens", "provider", "groq",
                "model", "llama-3.3-70b-versatile", "user", "user-B", "type", "prompt");

        assertThat(tokensA).isEqualTo(100.0);
        assertThat(tokensB).isEqualTo(200.0);
    }

    // ── recordChatCall (estimado) ──────────────────────────────────────────────

    @Test
    void registra_request_e_tokens_estimados_no_recordChatCall() {
        String question = "O que é RAG?";   // 12 chars → ~3 tokens
        String answer   = "RAG combina busca com geração.";  // 30 chars → ~7 tokens
        aiMetrics.recordChatCall(question, answer);

        double requests = registry.find("ai.chat.requests").counter().count();
        double estimated = registry.find("ai.chat.tokens.estimated").counter().count();

        assertThat(requests).isEqualTo(1.0);
        assertThat(estimated).isGreaterThan(0);
    }

    // ── recordEmbeddingCall ───────────────────────────────────────────────────

    @Test
    void registra_embedding_requests_em_lote() {
        aiMetrics.recordEmbeddingCall(5);
        aiMetrics.recordEmbeddingCall(3);

        double total = registry.find("ai.embedding.requests").counter().count();
        assertThat(total).isEqualTo(8.0);
    }

    // ── estimateCostUsd ────────────────────────────────────────────────────────

    @Test
    void custo_zero_para_zero_tokens() {
        assertThat(AiMetrics.estimateCostUsd(0, 0)).isEqualTo(0.0);
    }

    @Test
    void custo_apenas_input() {
        // 1M tokens input × $0,59/M = $0,59
        assertThat(AiMetrics.estimateCostUsd(1_000_000, 0)).isCloseTo(0.59, within(0.0001));
    }

    @Test
    void custo_apenas_output() {
        // 1M tokens output × $0,79/M = $0,79
        assertThat(AiMetrics.estimateCostUsd(0, 1_000_000)).isCloseTo(0.79, within(0.0001));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Busca contador por nome + pares de tags (chave, valor, chave, valor, ...). */
    private double counter(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        Counter c = search.counter();
        assertThat(c).as("Contador '%s' com tags %s não encontrado", name, java.util.Arrays.toString(tags))
                .isNotNull();
        return c.count();
    }
}
