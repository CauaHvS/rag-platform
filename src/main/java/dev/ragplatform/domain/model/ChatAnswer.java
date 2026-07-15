package dev.ragplatform.domain.model;

import java.util.List;

/**
 * Resultado de uma pergunta ao pipeline RAG.
 *
 * answer  — texto gerado pelo LLM com base nos trechos recuperados.
 * sources — chunks usados como contexto, ordenados por similaridade decrescente.
 */
public record ChatAnswer(String answer, List<SimilarChunk> sources) {}
