package dev.ragplatform.domain.model;

/**
 * Modo de busca de chunks para RAG e pesquisa direta.
 *
 * VECTOR  — somente similaridade coseno (embedding).
 * HYBRID  — RRF (Reciprocal Rank Fusion) de vetorial + BM25 full-text (padrão).
 *           Mais robusto: FTS recupera matches exatos; vetorial recupera semântica.
 */
public enum SearchMode {
    VECTOR,
    HYBRID
}
