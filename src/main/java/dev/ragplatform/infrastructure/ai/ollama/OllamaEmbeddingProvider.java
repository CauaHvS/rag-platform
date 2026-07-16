package dev.ragplatform.infrastructure.ai.ollama;

import dev.ragplatform.domain.exception.AiUnavailableException;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Provedor de embeddings via Ollama (local).
 * Ativo apenas quando app.embedding.provider=ollama.
 *
 * Resiliência:
 *   - @Retry(name="ollama-embed")        — 2 tentativas com backoff de 500 ms
 *   - @CircuitBreaker(name="ollama-embed") — abre após 60% de falhas em 5 chamadas
 *
 * Fallback de embedQuery(): retorna vetor zero — a busca híbrida continua
 *   funcionando via BM25 (full-text), degradando graciosamente em vez de falhar.
 *
 * Fallback de embedDocuments(): lança AiUnavailableException — a ingestão fica
 *   com status FAILED (intencionalmente: não queremos gravar vetores inválidos).
 */
@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);
    private static final int DIMENSION = 768;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String model;

    public OllamaEmbeddingProvider(
            RestClient.Builder builder,
            @Value("${app.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.embedding.model:nomic-embed-text}") String model) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl).build();
        this.model = model;
        log.info("OllamaEmbeddingProvider ativo: model={}, baseUrl={}", model, baseUrl);
    }

    @Override
    @CircuitBreaker(name = "ollama-embed")
    @Retry(name = "ollama-embed", fallbackMethod = "embedDocumentFallback")
    public float[] embedDocument(String text) {
        return embedDocuments(List.of(text)).get(0);
    }

    /**
     * Para queries de busca: fallback retorna vetor zero.
     * A busca híbrida continua via BM25; apenas o componente vetorial é neutralizado.
     */
    @Override
    @CircuitBreaker(name = "ollama-embed")
    @Retry(name = "ollama-embed", fallbackMethod = "embedQueryFallback")
    public float[] embedQuery(String text) {
        return embedDocument(text);
    }

    /**
     * Para ingestão: fallback lança exceção para marcar o documento como FAILED.
     */
    @Override
    @CircuitBreaker(name = "ollama-embed")
    @Retry(name = "ollama-embed", fallbackMethod = "embedDocumentsFallback")
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        var request = new EmbedRequest(model, texts);
        var response = restClient.post()
                .uri("/api/embed")
                .body(request)
                .retrieve()
                .body(EmbedResponse.class);

        if (response == null || response.embeddings() == null) {
            throw new IllegalStateException("Ollama retornou resposta vazia para embeddings.");
        }
        log.debug("Embeddings gerados: {} vetores de dim {}", response.embeddings().size(), DIMENSION);
        return response.embeddings().stream()
                .map(this::toFloatArray)
                .toList();
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    /** Fallback para embedQuery: vetor zero — BM25 assume sozinho na busca híbrida. */
    public float[] embedQueryFallback(String text, Throwable ex) {
        log.warn("Ollama embedding indisponível para query (fallback=vetor zero): {}", ex.getMessage());
        return new float[DIMENSION];
    }

    /** Fallback para embedDocument durante ingestão: falha explícita. */
    public float[] embedDocumentFallback(String text, Throwable ex) {
        log.error("Ollama embedding indisponível para ingestão: {}", ex.getMessage());
        throw new AiUnavailableException("Provedor de embeddings indisponível durante ingestão.", ex);
    }

    /** Fallback para embedDocuments em lote durante ingestão: falha explícita. */
    public List<float[]> embedDocumentsFallback(List<String> texts, Throwable ex) {
        log.error("Ollama embedding indisponível para ingestão em lote: {}", ex.getMessage());
        throw new AiUnavailableException("Provedor de embeddings indisponível durante ingestão.", ex);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
        return arr;
    }

    // ── DTOs internos ────────────────────────────────────────────────────────

    record EmbedRequest(String model, List<String> input) {}
    record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
