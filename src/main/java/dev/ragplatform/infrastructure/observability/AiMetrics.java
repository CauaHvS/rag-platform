package dev.ragplatform.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Métricas de observabilidade para chamadas de IA.
 *
 * Contadores expostos via /actuator/metrics:
 *
 *   ai.chat.requests          — total de requisições de chat (qualquer provider)
 *   ai.chat.tokens.estimated  — tokens estimados (1 token ≈ 4 chars; fallback quando API não retorna usage)
 *   ai.embedding.requests     — textos enviados ao provedor de embeddings
 *
 *   ai.tokens{provider,model,user,type=prompt|completion}
 *                             — tokens REAIS reportados pelo provider (ex.: Groq)
 *   ai.cost.usd{provider,model,user}
 *                             — custo estimado em USD baseado nos tokens reais e tabela de preços
 *
 * Preços de referência (Groq, llama-3.3-70b-versatile, jul/2026):
 *   input  = $0,59 / 1M tokens
 *   output = $0,79 / 1M tokens
 * Atualize INPUT_PER_MILLION / OUTPUT_PER_MILLION quando os preços mudarem.
 */
@Service
public class AiMetrics {

    // Preços Groq llama-3.3-70b-versatile (USD por milhão de tokens)
    private static final double INPUT_PER_MILLION  = 0.59;
    private static final double OUTPUT_PER_MILLION = 0.79;

    private final MeterRegistry registry;

    // Contadores sem tag — contagem geral para qualquer provider
    private final Counter chatRequestsCounter;
    private final Counter chatTokensEstimatedCounter;
    private final Counter embeddingRequestsCounter;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
        chatRequestsCounter = Counter.builder("ai.chat.requests")
                .description("Total de requisições de chat")
                .register(registry);
        chatTokensEstimatedCounter = Counter.builder("ai.chat.tokens.estimated")
                .description("Tokens estimados (fallback quando provider não reporta usage)")
                .register(registry);
        embeddingRequestsCounter = Counter.builder("ai.embedding.requests")
                .description("Total de textos enviados ao provedor de embeddings")
                .register(registry);
    }

    /**
     * Registra tokens REAIS reportados pelo provider e custo estimado em USD.
     * Chamado pelo adapter do provider (ex.: GroqChatProvider) após cada chamada bem-sucedida.
     *
     * @param provider       nome do provider (ex.: "groq")
     * @param model          modelo usado (ex.: "llama-3.3-70b-versatile")
     * @param userId         UUID do usuário autenticado (ou "anonymous")
     * @param promptTokens   tokens de entrada reportados pelo provider
     * @param completionTokens tokens de saída reportados pelo provider
     */
    public void recordChatTokens(String provider, String model, String userId,
                                  int promptTokens, int completionTokens) {
        Counter.builder("ai.tokens")
                .tag("provider", provider)
                .tag("model", model)
                .tag("user", userId)
                .tag("type", "prompt")
                .description("Tokens de prompt reportados pelo provider")
                .register(registry)
                .increment(promptTokens);

        Counter.builder("ai.tokens")
                .tag("provider", provider)
                .tag("model", model)
                .tag("user", userId)
                .tag("type", "completion")
                .description("Tokens de completion reportados pelo provider")
                .register(registry)
                .increment(completionTokens);

        double costUsd = estimateCostUsd(promptTokens, completionTokens);
        Counter.builder("ai.cost.usd")
                .tag("provider", provider)
                .tag("model", model)
                .tag("user", userId)
                .description("Custo estimado em USD (baseado em preços de referência)")
                .register(registry)
                .increment(costUsd);
    }

    /**
     * Registra uma chamada de chat com estimativa de tokens.
     * Usado pelo ChatService para contagem geral e como fallback
     * quando o provider não reporta usage (ex.: FakeChatProvider).
     */
    public void recordChatCall(String question, String answer) {
        chatRequestsCounter.increment();
        chatTokensEstimatedCounter.increment(estimateTokens(question) + estimateTokens(answer));
    }

    /**
     * Registra chamadas de embedding (uma por texto enviado ao provedor).
     *
     * @param count número de textos processados neste lote
     */
    public void recordEmbeddingCall(int count) {
        embeddingRequestsCounter.increment(count);
    }

    /** Custo estimado em USD a partir de tokens reais e tabela de preços de referência. */
    public static double estimateCostUsd(int promptTokens, int completionTokens) {
        return (promptTokens * INPUT_PER_MILLION + completionTokens * OUTPUT_PER_MILLION) / 1_000_000.0;
    }

    /** Estimativa simples: 1 token ≈ 4 caracteres (regra de bolso para pt-BR/en). */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
