package dev.ragplatform.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Métricas de observabilidade para chamadas de IA.
 *
 * Contadores expostos via /actuator/metrics:
 *   ai.chat.requests          — total de chamadas ao LLM de chat
 *   ai.chat.tokens.estimated  — tokens estimados (input + output, regra 1 token ≈ 4 chars)
 *   ai.embedding.requests     — total de textos enviados ao provedor de embeddings
 */
@Service
public class AiMetrics {

    private final Counter chatRequestsCounter;
    private final Counter chatTokensCounter;
    private final Counter embeddingRequestsCounter;

    public AiMetrics(MeterRegistry registry) {
        chatRequestsCounter = Counter.builder("ai.chat.requests")
                .description("Total de chamadas ao LLM de chat")
                .register(registry);
        chatTokensCounter = Counter.builder("ai.chat.tokens.estimated")
                .description("Total de tokens estimados (input + output) nas chamadas de chat")
                .register(registry);
        embeddingRequestsCounter = Counter.builder("ai.embedding.requests")
                .description("Total de textos enviados ao provedor de embeddings")
                .register(registry);
    }

    /**
     * Registra uma chamada de chat concluída.
     *
     * @param question  texto da pergunta do usuário
     * @param answer    resposta gerada pelo LLM
     */
    public void recordChatCall(String question, String answer) {
        chatRequestsCounter.increment();
        chatTokensCounter.increment(estimateTokens(question) + estimateTokens(answer));
    }

    /**
     * Registra chamadas de embedding (uma por texto enviado ao provedor).
     *
     * @param count número de textos processados neste lote
     */
    public void recordEmbeddingCall(int count) {
        embeddingRequestsCounter.increment(count);
    }

    /** Estimativa simples: 1 token ≈ 4 caracteres (regra de bolso para pt-BR/en). */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
