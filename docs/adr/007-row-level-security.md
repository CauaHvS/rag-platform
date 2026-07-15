# ADR 007 - Modelo de Isolamento Multiusuário: SQL Filter + RLS

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

A plataforma é multiusuária: cada usuário sobe seus próprios documentos e jamais
deve ver dados de outro. A falha de isolamento em uma plataforma RAG é especialmente
grave: o sistema responderia perguntas com contexto de documentos de terceiros,
possivelmente expondo informações confidenciais.

Existem duas abordagens ortogonais de isolamento:

1. **Filtro no SQL da aplicação**: todo SELECT/INSERT/UPDATE/DELETE inclui
   `WHERE owner_id = :userId` explícito no código.
2. **Row Level Security (RLS) no banco**: o Postgres aplica uma política de filtro
   automaticamente a cada query, independente do código da aplicação.

O CLAUDE.md define que "o filtro por owner_id acontece na query SQL" (já implementado
desde a Fatia 1) e menciona "Considere Row Level Security no Postgres como defesa em
profundidade (ADR)".

## Decisão

**Usar ambas as camadas:** filtro SQL explícito como barreira primária + RLS como
barreira secundária (defesa em profundidade).

### Barreira primária: filtro `owner_id` no SQL

Todos os repositórios filtram `owner_id = :userId` explicitamente:
- `DocumentRepository.findByIdAndOwner(UUID id, UUID ownerId)`
- `VectorJdbcRepository`: `WHERE owner_id = ?::uuid` em ambas as CTEs do RRF
- `ChatTurnRepository.findByOwner(UUID ownerId)`

Testado em `HybridSearchIT`, `HistoryIT`, `ChatIT`, `DocumentIT` com cenário
usuário-A-não-vê-B via API.

### Barreira secundária: RLS

Policy em `documents`, `chunks` e `chat_turns`:

```sql
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;

CREATE POLICY documents_rls_policy ON documents
    USING (
        CASE
            WHEN NULLIF(current_setting('app.current_user_id', true), '') IS NULL THEN TRUE
            ELSE owner_id = current_setting('app.current_user_id', true)::uuid
        END
    )
    WITH CHECK ( ... mesma expressão ... );
```

`FORCE ROW LEVEL SECURITY` é obrigatório porque o dono da tabela (usuário `ragplatform`)
é o mesmo papel usado pela aplicação. Sem `FORCE`, o dono bypassa RLS.

### Propagação do userId

Cadeia de propagação para cada requisição HTTP autenticada:

```
JwtAuthenticationFilter → SecurityContext
    → RlsFilter.doFilterInternal() → RlsContext.set(userId)
        → RlsDataSourceWrapper.applyContext(conn)
            → set_config('app.current_user_id', userId, false)
```

`RlsContext` usa `InheritableThreadLocal` para que virtual threads (SSE streaming)
herdem o userId do thread HTTP pai sem propagação manual.

`RlsDataSourceWrapper` (BeanPostProcessor) envolve o bean `dataSource` e intercepta
todo `getConnection()`, garantindo que a variável de sessão é definida antes de
qualquer query.

### Política de bypass para contextos sem usuário

Quando `app.current_user_id` é vazio ou não definido:
- `NULLIF('', '') IS NULL` → `NULL IS NULL` → TRUE → todas as linhas visíveis
- Background jobs (ingestão), Flyway migrations e testes sem contexto HTTP
  operam sem restrição de RLS

### Prova de funcionamento

`RlsIT` testa a camada de banco diretamente via `JdbcTemplate` + `TransactionTemplate`,
sem passar pelos endpoints HTTP:
1. `SET LOCAL app.current_user_id = ''` → documento de A visível (bypass)
2. `SET LOCAL app.current_user_id = userA_id` → documento de A visível
3. `SET LOCAL app.current_user_id = userB_id` → documento de A **invisível** (RLS bloqueia)
4. Idem para tabela `chunks`

## Consequências

### Positivas
- **Defesa em profundidade**: um bug no código Java que esqueça o filtro `owner_id`
  não vaza dados — o banco bloqueia a nível de storage.
- **Prova com teste dedicado**: `RlsIT` verifica o RLS independente da aplicação,
  usando SQL direto. Se a política for removida ou quebrada, o teste falha no CI.
- **Cobertura de INSERT**: `WITH CHECK` impede que a aplicação insira linhas com
  `owner_id` errado (bug de lógica de negócio capturado no banco).
- **Transparente para o código**: após a configuração da infra (BeanPostProcessor +
  Filter), nenhum código de repositório precisa ser alterado.

### Negativas
- **`FORCE ROW LEVEL SECURITY` com um único usuário de banco**: a configuração ideal
  seria dois papéis (`ragplatform_app` para a aplicação, `ragplatform_admin` para
  Flyway) sem FORCE. Com um papel, FORCE é necessário mas significa que qualquer
  acesso direto ao banco via `psql` com o usuário `ragplatform` também é restrito.
  Para manutenção de emergência, é necessário `SET app.current_user_id = ''` antes
  das queries.
- **`set_config` em cada `getConnection()`**: adiciona uma query extra por conexão
  do pool. Com HikariCP, isso ocorre por conexão física e por borrow lógico. O custo
  é mínimo (uma round-trip ultra-rápida para o banco local/docker), mas existe.
- **`InheritableThreadLocal` em alta concorrência**: virtual threads herdam valores
  do thread pai, o que pode causar vazamento de contexto se threads forem reutilizados
  de forma inesperada. Para o perfil de portfólio (baixa concorrência), é aceitável.
  Em produção de alta escala, prefira Scoped Values (Project Loom, Java 21+).
- **Não cobre tabela `users`**: usuários podem ver outros usuários via `CustomUserDetailsService`
  (busca por email). Para o escopo atual, o acesso a `users` é feito apenas para
  autenticação (sem exposição de dados de outros usuários via API). RLS em `users`
  seria redundante e complicaria o login.

## Alternativas consideradas

### RLS puro, sem filtro SQL
Rejeitado. Transfere toda a responsabilidade para o banco; bugs no `set_config`
(ex: BeanPostProcessor não aplicado em teste) deixariam a aplicação sem isolamento.
Filtro explícito no código é mais visível para revisores e ferramentas de análise
estática.

### Filtro SQL puro, sem RLS
Rejeitado como única camada. A aplicação é o único ponto de enforcement; um refactor
que remova o `WHERE owner_id` por acidente (pull request mal revisado, busca com
JOIN incorreto) vaza dados sem nenhuma barreira secundária.

### Schema separado por usuário (schema-per-tenant)
Considerado. Isolamento completo no banco, sem RLS. Rejeitado:
(a) requer CREATE SCHEMA e Flyway multitenancy para cada usuário — complexidade
operacional altíssima;
(b) inviabiliza busca cross-document do mesmo usuário com JOINs simples;
(c) padrão adequado para B2B com dezenas de tenants, não para B2C com milhares
de usuários.

### Banco separado por usuário
Não considerado seriamente. Não escala operacionalmente.

### Scoped Values (Java 21 Preview) em vez de InheritableThreadLocal
Preferível tecnicamente para virtual threads (imutável, sem herança acidental).
Rejeitado porque ainda estava em preview no Java 21 LTS e a API não estava estável.
Candidato para atualização quando sair de preview.

## Referências
- PostgreSQL RLS documentation: https://www.postgresql.org/docs/current/ddl-rowsecurity.html
- `RlsContext.java` — InheritableThreadLocal holder
- `RlsFilter.java` — propagação do userId para o contexto
- `RlsDataSourceWrapper.java` — set_config por conexão
- `V8__row_level_security.sql` — migration com políticas
- `RlsIT.java` — teste de integração que prova o RLS no nível de banco
- ADR 001 - Arquitetura hexagonal (onde a infra de RLS mora)
