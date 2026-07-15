package dev.ragplatform.infrastructure.web.search;

import dev.ragplatform.domain.model.SimilarChunk;

import java.util.UUID;

public record SearchResponse(
        UUID documentId,
        UUID chunkId,
        String content,
        int charStart,
        int charEnd,
        double similarity
) {
    public static SearchResponse from(SimilarChunk c) {
        return new SearchResponse(
                c.documentId(), c.chunkId(),
                c.content(), c.charStart(), c.charEnd(),
                c.similarity());
    }
}
