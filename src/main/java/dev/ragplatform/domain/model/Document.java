package dev.ragplatform.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root do domínio de documentos.
 * Sem dependência de framework; imutável por ser record.
 */
public record Document(
        UUID id,
        UUID ownerId,
        String originalName,
        String mimeType,
        long fileSize,
        String storagePath,
        DocumentStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    /** Cria um novo documento recém-carregado, com status PENDING. */
    public static Document newUpload(UUID id, UUID ownerId, String originalName,
                                     String mimeType, long fileSize, String storagePath) {
        Instant now = Instant.now();
        return new Document(id, ownerId, originalName, mimeType, fileSize,
                storagePath, DocumentStatus.PENDING, null, now, now);
    }

    public Document withStatus(DocumentStatus newStatus) {
        return new Document(id, ownerId, originalName, mimeType, fileSize,
                storagePath, newStatus, errorMessage, createdAt, Instant.now());
    }

    public Document withError(String error) {
        return new Document(id, ownerId, originalName, mimeType, fileSize,
                storagePath, DocumentStatus.FAILED, error, createdAt, Instant.now());
    }
}
