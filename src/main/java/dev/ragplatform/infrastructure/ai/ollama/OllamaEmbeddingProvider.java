package dev.ragplatform.infrastructure.ai.ollama;

import dev.ragplatform.domain.port.out.EmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Provedor de embeddings via Ollama (local).
 * Ativo apenas quando app.embedding.provider=ollama.
 *
 * Endpoint: POST {base-url}/api/embed
 * Modelo padrão: nomic-embed-text (768 dimensões nativas).
 *
 * Em produção, trocar pelo adaptador OpenAI ou Cohere (ver ADR 002)
 * sem alterar nenhuma outra classe.
 */
@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);
    private static final int DIMENSION = 768;

    private final RestClient restClient;
    private final String model;

    public OllamaEmbeddingProvider(
            RestClient.Builder builder,
            @Value("${app.embedding.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.embedding.model:nomic-embed-text}") String model) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.model = model;
        log.info("OllamaEmbeddingProvider ativo: model={}, baseUrl={}", model, baseUrl);
    }

    @Override
    public float[] embedDocument(String text) {
        return embedDocuments(List.of(text)).get(0);
    }

    @Override
    public float[] embedQuery(String text) {
        return embedDocument(text);
    }

    @Override
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

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
        return arr;
    }

    // ── DTOs internos ────────────────────────────────────────────────────────

    record EmbedRequest(String model, List<String> input) {}

    record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
