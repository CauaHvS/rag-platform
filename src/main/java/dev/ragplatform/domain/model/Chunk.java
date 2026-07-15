package dev.ragplatform.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Trecho de texto extraído de um documento.
 * Ainda sem embedding (será adicionado na Fatia 2.2).
 */
public record Chunk(
        UUID id,
        UUID documentId,
        UUID ownerId,
        int chunkIndex,
        String content,
        int charStart,
        int charEnd,
        Instant createdAt
) {
    public static Chunk of(UUID documentId, UUID ownerId,
                           int index, String content, int charStart, int charEnd) {
        return new Chunk(UUID.randomUUID(), documentId, ownerId,
                index, content, charStart, charEnd, Instant.now());
    }
}
