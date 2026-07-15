package dev.ragplatform.domain.event;

import java.util.UUID;

/**
 * Publicado após o upload de um documento ser persistido com sucesso.
 * Escutado por IngestionListener para disparar o pipeline assíncrono.
 */
public record DocumentUploadedEvent(UUID documentId, UUID ownerId) {}
