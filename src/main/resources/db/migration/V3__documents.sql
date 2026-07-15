CREATE TABLE documents (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    original_name VARCHAR(500)  NOT NULL,
    mime_type     VARCHAR(100)  NOT NULL,
    file_size     BIGINT        NOT NULL,
    storage_path  VARCHAR(1000) NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT documents_status_check
        CHECK (status IN ('PENDING','EXTRACTING','CHUNKING','EMBEDDING','READY','FAILED'))
);

-- Busca de documentos por dono é o acesso mais frequente; índice obrigatório.
CREATE INDEX documents_owner_id_idx ON documents(owner_id);
