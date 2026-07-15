package dev.ragplatform.infrastructure.ai.fake;

import dev.ragplatform.domain.port.out.EmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter fake do EmbeddingProvider.
 *
 * Ativo quando app.embedding.provider=fake (default) ou quando a propriedade não está definida.
 * Em dev/prod, substituir por OllamaEmbeddingProvider (EMBEDDING_PROVIDER=ollama).
 *
 * Retorna vetores de zeros — sem rede, sem cota, sem Ollama rodando.
 * Garante que testes de integração não dependam de infraestrutura externa de IA.
 */
@Component
@ConditionalOnProperty(name = "app.embedding.provider", havingValue = "fake", matchIfMissing = true)
public class FakeEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 768;

    @Override
    public float[] embedDocument(String text) {
        return new float[DIMENSION];
    }

    @Override
    public float[] embedQuery(String text) {
        return new float[DIMENSION];
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream()
                .map(t -> new float[DIMENSION])
                .toList();
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
