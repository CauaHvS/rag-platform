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
     * Busca os k chunks mais próximos do queryEmbedding dentro do espaço do dono.
     * Usa distância coseno (<=>). Chunks sem embedding são excluídos.
     */
    List<SimilarChunk> findSimilar(UUID ownerId, float[] queryEmbedding, int k);
}
