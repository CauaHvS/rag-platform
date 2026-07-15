-- Chunks de texto extraídos dos documentos.
-- A coluna embedding (vector) será adicionada na Fatia 2.2 via V5.
CREATE TABLE chunks (
    id           UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id  UUID    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    owner_id     UUID    NOT NULL,   -- denormalizado para isolamento e RLS futuro
    chunk_index  INT     NOT NULL,
    content      TEXT    NOT NULL,
    char_start   INT     NOT NULL,
    char_end     INT     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chunks_document_chunk_unique UNIQUE (document_id, chunk_index)
);

CREATE INDEX chunks_document_id_idx ON chunks(document_id);
-- Índice por dono para buscas futuras com isolamento multiusuário
CREATE INDEX chunks_owner_id_idx    ON chunks(owner_id);
