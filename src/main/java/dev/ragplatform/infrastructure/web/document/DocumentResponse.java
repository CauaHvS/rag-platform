package dev.ragplatform.infrastructure.web.document;

import dev.ragplatform.domain.model.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String originalName,
        String mimeType,
        long fileSize,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.id(), d.originalName(), d.mimeType(), d.fileSize(),
                d.status().name(), d.errorMessage(), d.createdAt(), d.updatedAt()
        );
    }
}
