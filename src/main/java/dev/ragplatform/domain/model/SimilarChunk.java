package dev.ragplatform.domain.model;

import java.util.UUID;

/**
 * Resultado de busca por similaridade vetorial.
 * similarity = 1 - cosine_distance (0..1, maior = mais relevante).
 */
public record SimilarChunk(
        UUID chunkId,
        UUID documentId,
        String content,
        int charStart,
        int charEnd,
        double similarity
) {}
