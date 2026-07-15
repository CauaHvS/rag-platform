-- Row Level Security nas tabelas com dados de usuário.
--
-- IMPORTANTE SOBRE SUPERUSER E RLS:
-- Superusers do Postgres ignoram RLS mesmo com FORCE ROW LEVEL SECURITY.
-- A imagem oficial postgres:<ver> cria o POSTGRES_USER como superuser.
-- Para RLS ter efeito em produção, a aplicação deve conectar como um papel
-- não-superuser (ragplatform_app, criado abaixo). Flyway conecta como superuser
-- para migrations DDL; a app usa ragplatform_app para DML.
--
-- Em dev/testes com usuário superuser, o RLS atua via SET LOCAL ROLE ragplatform_app
-- dentro da transação que precisa de isolamento (ver RlsIT).
--
-- Estratégia de bypass: app.current_user_id vazio ou ausente → todas as linhas
-- visíveis (background jobs, migrations). UUID definido → filtro por owner_id.

-- ── Papel da aplicação (não-superuser) ──────────────────────────────────────
-- Criado com IF NOT EXISTS para ser idempotente; pode já existir em prod.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ragplatform_app') THEN
        CREATE ROLE ragplatform_app NOSUPERUSER NOLOGIN NOINHERIT;
    END IF;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ragplatform_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ragplatform_app;

-- ── documents ────────────────────────────────────────────────────────────────

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;

CREATE POLICY documents_rls_policy ON documents
    USING (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    )
    WITH CHECK (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    );

-- ── chunks ───────────────────────────────────────────────────────────────────

ALTER TABLE chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunks FORCE ROW LEVEL SECURITY;

CREATE POLICY chunks_rls_policy ON chunks
    USING (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    )
    WITH CHECK (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    );

-- ── chat_turns ───────────────────────────────────────────────────────────────

ALTER TABLE chat_turns ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_turns FORCE ROW LEVEL SECURITY;

CREATE POLICY chat_turns_rls_policy ON chat_turns
    USING (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    )
    WITH CHECK (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    );
