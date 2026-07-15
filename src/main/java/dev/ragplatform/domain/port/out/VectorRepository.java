package dev.ragplatform.domain.port.out;

import dev.ragplatform.domain.model.SimilarChunk;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída: operações vetoriais sobre chunks.
 * O filtro por ownerId garante isolamento multiusuário na camada de dados.
 */
public interface VectorRepository {
    /** Persiste o embedding de um chunk já salvo. */
    void saveEmbedding(UUID chunkId, float[] embedding);

    /**
     * Busca vetorial pura: k chunks mais próximos por distância coseno.
     * Chunks sem embedding são excluídos.
     */
    List<SimilarChunk> findSimilar(UUID ownerId, float[] queryEmbedding, int k);

    /**
     * Busca híbrida: combina ranking vetorial e BM25 full-text via RRF.
     *
     * RRF score = 1/(k+rank_vetorial) + 1/(k+rank_fts), com k=60 (padrão).
     * Mais robusto que vetorial puro: FTS recupera matches exatos (termos técnicos,
     * nomes próprios) que embeddings podem não capturar.
     *
     * @param query texto da pergunta (usado para FTS, além de queryEmbedding para vetorial)
     */
    List<SimilarChunk> findSimilarHybrid(UUID ownerId, float[] queryEmbedding, String query, int k);
}
