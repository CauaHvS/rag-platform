package dev.ragplatform.domain.model;

public enum DocumentStatus {
    /** Arquivo recebido; aguardando processamento assíncrono. */
    PENDING,
    /** Job de extração de texto (OCR) em andamento. */
    EXTRACTING,
    /** Dividindo o texto em chunks. */
    CHUNKING,
    /** Gerando e armazenando embeddings no PgVector. */
    EMBEDDING,
    /** Pronto para consulta. */
    READY,
    /** Falha em alguma etapa do pipeline. Veja errorMessage. */
    FAILED
}
