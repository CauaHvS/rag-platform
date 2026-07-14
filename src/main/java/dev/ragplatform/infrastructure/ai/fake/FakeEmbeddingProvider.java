package dev.ragplatform.infrastructure.ai.fake;

import dev.ragplatform.domain.port.out.EmbeddingProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter fake do EmbeddingProvider.
 *
 * Ativo quando nenhum outro EmbeddingProvider está registrado no contexto Spring.
 * Em prod, este bean é substituído automaticamente pelo adapter real (Ollama ou OpenAI).
 *
 * Retorna vetores de zeros — sem rede, sem cota, sem Ollama rodando.
 * Garante que testes de integração não dependam de infraestrutura externa de IA.
 */
@Component
@ConditionalOnMissingBean(EmbeddingProvider.class)
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
