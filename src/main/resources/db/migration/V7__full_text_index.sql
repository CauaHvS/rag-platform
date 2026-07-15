-- Coluna tsvector gerada automaticamente pelo Postgres para busca full-text.
-- GENERATED ALWAYS AS STORED: computada na escrita, persistida no disco.
-- Configuração 'portuguese' aplica stemming e stop words em pt-BR.
ALTER TABLE chunks
    ADD COLUMN content_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('portuguese', content)) STORED;

-- Índice GIN é o tipo padrão para tsvector; suporta @@ e ts_rank eficientemente.
CREATE INDEX idx_chunks_content_tsv ON chunks USING gin(content_tsv);
