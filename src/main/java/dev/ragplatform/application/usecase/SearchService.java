package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.model.SearchMode;
import dev.ragplatform.domain.model.SimilarChunk;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import dev.ragplatform.domain.port.out.VectorRepository;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SearchService {

    private final EmbeddingProvider embeddingProvider;
    private final VectorRepository vectorRepository;

    public SearchService(EmbeddingProvider embeddingProvider, VectorRepository vectorRepository) {
        this.embeddingProvider = embeddingProvider;
        this.vectorRepository = vectorRepository;
    }

    /**
     * Busca de chunks no modo especificado.
     *
     * HYBRID (padrão): RRF vetorial + BM25 — mais preciso, preferido em produção.
     * VECTOR: apenas similaridade coseno — útil para diagnóstico e comparação.
     *
     * Isolamento garantido: owner_id filtrado no SQL de ambos os modos.
     */
    @Observed(name = "rag.search", contextualName = "busca-hibrida")
    @Transactional(readOnly = true)
    public List<SimilarChunk> search(UUID ownerId, String query, int k, SearchMode mode) {
        float[] queryEmbedding = embeddingProvider.embedQuery(query);
        return switch (mode) {
            case HYBRID -> vectorRepository.findSimilarHybrid(ownerId, queryEmbedding, query, k);
            case VECTOR -> vectorRepository.findSimilar(ownerId, queryEmbedding, k);
        };
    }
}
