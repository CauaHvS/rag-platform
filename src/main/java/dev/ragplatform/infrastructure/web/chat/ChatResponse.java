package dev.ragplatform.infrastructure.web.chat;

import dev.ragplatform.domain.model.ChatAnswer;
import dev.ragplatform.domain.model.SimilarChunk;

import java.util.List;
import java.util.UUID;

public record ChatResponse(String answer, List<SourceResponse> sources) {

    public record SourceResponse(
            UUID chunkId,
            UUID documentId,
            String content,
            double similarity
    ) {}

    public static ChatResponse from(ChatAnswer a) {
        List<SourceResponse> sources = a.sources().stream()
                .map(s -> new SourceResponse(s.chunkId(), s.documentId(), s.content(), s.similarity()))
                .toList();
        return new ChatResponse(a.answer(), sources);
    }

    /** Versão de SourceResponse mapeada a partir de SimilarChunk. */
    private static SourceResponse fromChunk(SimilarChunk s) {
        return new SourceResponse(s.chunkId(), s.documentId(), s.content(), s.similarity());
    }
}
