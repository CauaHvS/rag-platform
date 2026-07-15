package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.model.SimilarChunk;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import dev.ragplatform.domain.port.out.VectorRepository;
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
     * Busca semântica: embeda a query e retorna os k chunks mais próximos do dono.
     * O filtro por ownerId garante que o usuário não veja chunks de outros.
     */
    @Transactional(readOnly = true)
    public List<SimilarChunk> search(UUID ownerId, String query, int k) {
        float[] queryEmbedding = embeddingProvider.embedQuery(query);
        return vectorRepository.findSimilar(ownerId, queryEmbedding, k);
    }
}
