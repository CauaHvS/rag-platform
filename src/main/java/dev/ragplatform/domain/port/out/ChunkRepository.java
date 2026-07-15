package dev.ragplatform.domain.port.out;

import dev.ragplatform.domain.model.Chunk;

import java.util.List;
import java.util.UUID;

/** Porta de saída: persistência de chunks de texto. */
public interface ChunkRepository {
    List<Chunk> saveAll(List<Chunk> chunks);
    /** Remove todos os chunks de um documento. Usado para garantir idempotência no reprocessamento. */
    void deleteByDocumentId(UUID documentId);
    long countByDocumentId(UUID documentId);
}
