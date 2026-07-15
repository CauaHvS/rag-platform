package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.model.SimilarChunk;

import java.util.List;

/**
 * Contexto intermediário do pipeline RAG streaming.
 *
 * Produzido por ChatService.prepareStream() — contém os chunks recuperados
 * e o system prompt montado, prontos para o passo de geração de tokens.
 */
public record ChatStreamContext(List<SimilarChunk> sources, String systemPrompt) {}
