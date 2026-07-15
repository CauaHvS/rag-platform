-- Adiciona coluna de embedding aos chunks.
-- vector(768): dimensão do nomic-embed-text e do text-embedding-3-small (com dimensions=768).
-- Nullable: chunks recém-criados ficam sem embedding até o job de EMBEDDING concluir.
ALTER TABLE chunks ADD COLUMN embedding vector(768);

-- Índice HNSW para busca coseno em produção.
-- Criado AFTER DATA para não bloquear a migration; rebuild se necessário.
-- Em dev com poucos dados o seq-scan é mais rápido que HNSW, então criamos mas não força uso.
CREATE INDEX chunks_embedding_hnsw_idx
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
