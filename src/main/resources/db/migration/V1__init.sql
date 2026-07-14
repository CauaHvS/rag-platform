-- Habilita a extensão pgvector, necessária para armazenar e buscar embeddings.
-- A coluna vector(768) será criada nas migrations de Fase 3 (ingestão de chunks).
CREATE EXTENSION IF NOT EXISTS vector;
