# RAG Platform

Plataforma de perguntas e respostas sobre documentos privados usando
Retrieval-Augmented Generation (RAG). Projeto de portfólio focado em engenharia
de backend sênior: pipeline assíncrono idempotente, busca híbrida avaliada com
métricas quantitativas, isolamento multiusuário, resiliência e observabilidade.

## O que este projeto demonstra

- Pipeline de ingestão assíncrona com máquina de estados (PENDING → READY | FAILED),
  idempotente e retomável: reprocessar não duplica chunks.
- Busca híbrida vetorial + BM25 com Reciprocal Rank Fusion, avaliada com Recall@5
  e MRR contra um golden set — "melhorou" é afirmação com número, não opinião.
- Isolamento multiusuário garantido no SQL: filtro `owner_id` em toda query, testado
  com cenário A-não-vê-B.
- Resiliência com Resilience4j: Circuit Breaker (abre após 50% de falhas) + Retry
  com backoff exponencial + fallback gracioso diferenciado — embedding de query retorna
  vetor zero (busca continua via BM25); embedding de ingestão falha explicitamente
  (documento vai para FAILED, não grava vetor inválido).
- Cache de embeddings de consulta no Redis (SHA-256 + float[] Base64 little-endian,
  TTL 1h) e rate limit por usuário (Redis INCR/EXPIRE, 429 com Retry-After).
- Observabilidade: tokens reais por usuário (`ai.tokens{provider,model,user,type}`),
  custo estimado em USD (`ai.cost.usd`), hit rate do cache (`ai.embedding.cache.hits/misses`),
  circuit breaker state (`/actuator/circuitbreakers`), tracing distribuído (Zipkin).
- Streaming token a token via Server-Sent Events com virtual threads.
- Frontend React com upload + polling de status + chat com streaming + histórico.

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven |
| Banco | PostgreSQL 16 + pgvector |
| Cache / Rate limit | Redis 7 |
| Embeddings | Ollama — nomic-embed-text (768 dims); intercambiável sem refatoração |
| Chat | Groq — llama-3.3-70b-versatile |
| Resiliência | Resilience4j 2.3 (Circuit Breaker + Retry + fallback) |
| Migrations | Flyway |
| Testes | JUnit 5 + Testcontainers (58 testes) + AssertJ + Playwright (E2E) |
| Frontend | React 19, Vite, TypeScript, Tailwind v4, TanStack Query/Router |
| Infra | Docker Compose, Kubernetes + Helm, GitHub Actions CI (3 jobs) |

## Arquitetura

```
┌──────────────────────────────────────────────────────────────────┐
│  Frontend (React + Vite)   porta 80 via nginx                    │
│  Upload / Status / Chat SSE / Histórico                          │
└───────────────────┬──────────────────────────────────────────────┘
                    │ HTTP / SSE  (proxy nginx → app:8080)
┌───────────────────▼──────────────────────────────────────────────┐
│  Backend (Spring Boot 3.5)  porta 8080                           │
│                                                                  │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐              │
│  │ AuthService │  │ DocumentSvc  │  │  ChatSvc   │              │
│  └─────────────┘  └──────┬───────┘  └─────┬──────┘              │
│                           │ async          │                     │
│                    ┌──────▼───────┐  ┌────▼──────────────────┐  │
│                    │IngestionSvc  │  │ EmbeddingQueryCache    │  │
│                    │(extract/chunk│  │ (Redis, TTL 1h)        │  │
│                    │ /embed batch)│  └────┬──────────────────-┘  │
│                    └──────┬───────┘       │                     │
│                           │               │ EmbeddingProvider   │
│                    ┌──────▼───────────────▼──────────────┐      │
│                    │  VectorJdbcRepository               │      │
│                    │  findSimilar() | findSimilarHybrid() │      │
│                    │  RRF: 1/(60+rank_vec)+1/(60+rank_fts)│      │
│                    └──────────────────────────────────────┘      │
└───────┬──────────────────────────┬───────────────────────────────┘
        │ JDBC / JPA               │ RedisTemplate
┌───────▼──────────┐    ┌──────────▼──────────┐
│  PostgreSQL 16   │    │  Redis 7             │
│  + pgvector      │    │  cache + rate limit  │
│  HNSW idx (cos)  │    └─────────────────────┘
│  GIN idx (fts)   │
└──────────────────┘
```

**Arquitetura hexagonal** (Ports and Adapters): domínio sem framework, provedores
de IA intercambiáveis sem refatoração do núcleo. Ver ADR 001.

## Setup local (dev)

### Pré-requisitos

- Java 21+
- Docker + Docker Compose
- Ollama rodando localmente (para embeddings em dev)
- Node 22+ (para o frontend)

### 1. Subir infraestrutura

```bash
docker compose up -d postgres redis
```

### 2. Baixar modelo de embedding

```bash
ollama pull nomic-embed-text
```

### 3. Configurar variáveis de ambiente

Crie um arquivo `.env` na raiz (nunca commitar):

```env
# Obrigatório em dev
JWT_SECRET=<base64-32-bytes-aleatorios>
GROQ_API_KEY=<sua-chave-groq>

# Embedding: ollama (dev) ou openai (prod)
EMBEDDING_PROVIDER=ollama
EMBEDDING_BASE_URL=http://localhost:11434

# Defaults — não precisam ser definidos em dev
# CHAT_PROVIDER=groq
# STORAGE_LOCAL_PATH=./uploads
# REDIS_HOST=localhost
# REDIS_PORT=6379
# DATABASE_URL=jdbc:postgresql://localhost:5432/ragplatform
```

Gerar um JWT_SECRET válido:

```bash
openssl rand -base64 32
```

### 4. Rodar o backend

```bash
./mvnw spring-boot:run
```

### 5. Rodar o frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend disponível em `http://localhost:5173`.

## Build e testes

```bash
# Compilar + testes unitários + testes de integração (Testcontainers)
./mvnw verify

# Rodar apenas uma classe IT
./mvnw verify -Dit.test="HybridSearchIT"

# Build de produção completo (Docker)
docker compose up -d --build
```

Os testes de integração sobem PostgreSQL + Redis em containers automaticamente via
Testcontainers. Não é necessário nenhuma infra rodando para executá-los.

## Variáveis de ambiente (produção)

| Variável | Descrição | Padrão |
|---|---|---|
| `JWT_SECRET` | Chave HMAC-SHA256 para JWT (base64, 32 bytes) | **Obrigatório em prod** |
| `GROQ_API_KEY` | Chave da API Groq | **Obrigatório** com `CHAT_PROVIDER=groq` |
| `CHAT_PROVIDER` | `fake` (testes) ou `groq` | `fake` |
| `EMBEDDING_PROVIDER` | `fake` (testes) ou `ollama` | `fake` |
| `EMBEDDING_BASE_URL` | URL base do Ollama | `http://localhost:11434` |
| `EMBEDDING_MODEL` | Modelo de embedding | `nomic-embed-text` |
| `DATABASE_URL` | JDBC URL do PostgreSQL | `jdbc:postgresql://localhost:5432/ragplatform` |
| `DATABASE_USERNAME` | Usuário do banco | `ragplatform` |
| `DATABASE_PASSWORD` | Senha do banco | `ragplatform` |
| `REDIS_HOST` | Host do Redis | `localhost` |
| `REDIS_PORT` | Porta do Redis | `6379` |
| `STORAGE_LOCAL_PATH` | Diretório de uploads | `./uploads` |
| `CHAT_RATE_LIMIT` | Max requests de chat por minuto por usuário | `20` |
| `ZIPKIN_ENDPOINT` | Endpoint do Zipkin para tracing | `http://localhost:9411/api/v2/spans` |

**API keys nunca no código nem no repositório.**

## API

Documentação completa em `docs/api/openapi.yml`.

Endpoints principais:

| Método | Path | Descrição |
|---|---|---|
| POST | `/auth/register` | Registrar usuário |
| POST | `/auth/login` | Autenticar (retorna JWT) |
| POST | `/api/documents` | Upload de documento (202 + status PENDING) |
| GET | `/api/documents` | Listar documentos do usuário |
| GET | `/api/documents/{id}` | Consultar status da ingestão |
| GET | `/api/search?q=...&k=5&mode=hybrid` | Busca híbrida |
| POST | `/api/chat` | Pergunta RAG (resposta completa) |
| POST | `/api/chat/stream` | Pergunta RAG (streaming SSE token a token) |
| GET | `/api/chat/history` | Histórico de conversas |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/metrics` | Métricas Micrometer |

## Pipeline de ingestão

```
Upload (POST /api/documents)
  │
  ▼ status: PENDING
  │
  ├─ EXTRACTING: extrai texto (PDF via PDFBox, .txt/.md direto)
  ├─ CHUNKING: divide em chunks 1500 chars, overlap 200 chars
  ├─ EMBEDDING: gera vetores em batch via EmbeddingProvider
  │
  ▼ status: READY  (ou FAILED com errorMessage)
```

Idempotente: reprocessar um documento apaga os chunks antigos e recria.
Retomável: falha no chunk 300/500 não refaz os 299 anteriores (implementação futura
com checkpoint por chunk).

## Pipeline RAG (chat)

```
Pergunta
  │
  ├─ 1. EmbeddingQueryCache (Redis): hit? → vetor cacheado
  │  └─ miss? → EmbeddingProvider.embed() + salvar no cache
  │
  ├─ 2. VectorRepository.findSimilarHybrid()
  │      RRF: 1/(60+rank_vec) + 1/(60+rank_fts)
  │      Filtro owner_id no SQL (isolamento multiusuário)
  │
  ├─ 3. Montar system prompt com chunks como contexto
  │
  ├─ 4. ChatProvider.stream() — Groq com Retry (3×, backoff 500ms×2)
  │      Fallback: AiUnavailableException → HTTP 503
  │
  └─ 5. Salvar ChatTurn (pergunta + resposta) no banco
```

## Avaliação de qualidade de recuperação

```bash
# Rodar avaliação contra golden set
./mvnw verify -Dit.test="EvaluationIT" -Dlogging.level.dev.ragplatform=INFO
```

Golden set: 3 documentos, 5 queries. Thresholds são gates do build (CI falha se não atingidos).

| Configuração | Recall@5 | MRR | Observação |
|---|---|---|---|
| Híbrida (BM25 + vetorial, RRF) | ≥ 0.80 | ≥ 0.60 | Gate do CI |
| Vetorial pura | baseline | baseline | Comparação |

> Com `FakeEmbeddingProvider` (vetores zero), o BM25 responde sozinho — resultado determinístico.
> Em produção com Ollama `nomic-embed-text`, a componente vetorial contribui para queries semânticas
> onde os termos exatos diferem do documento. Recall@5 empírico tende a ser maior.

## Métricas de observabilidade

Expostas em `/actuator/metrics/<nome>` (Micrometer → Prometheus-compatível):

| Métrica | Tags | Descrição |
|---|---|---|
| `ai.tokens` | provider, model, user, type=prompt\|completion | Tokens reais reportados pelo provider |
| `ai.cost.usd` | provider, model, user | Custo estimado em USD |
| `ai.chat.requests` | — | Total de requisições de chat |
| `ai.chat.tokens.estimated` | — | Tokens estimados (fallback para providers sem usage) |
| `ai.embedding.requests` | — | Textos enviados ao provedor de embedding (cache miss) |
| `ai.embedding.cache.hits` | — | Queries servidas pelo cache Redis |
| `ai.embedding.cache.misses` | — | Queries que foram ao provedor |

Hit rate do cache: `cache.hits / (cache.hits + cache.misses)`

Estado dos circuit breakers: `/actuator/circuitbreakers`
Estado de retries: `/actuator/retries`
Tracing: Zipkin em `http://localhost:9411`

## Decisões de arquitetura (ADRs)

| ADR | Decisão |
|---|---|
| [001](docs/adr/001-arquitetura-hexagonal.md) | Arquitetura Hexagonal (Ports and Adapters) |
| [002](docs/adr/002-provedores-ia.md) | ChatProvider=Groq, EmbeddingProvider=Ollama (local) |
| [003](docs/adr/003-estrategia-chunking.md) | Chunking por janela deslizante (1500 chars, overlap 200) |
| [004](docs/adr/004-indice-pgvector.md) | Índice HNSW (m=16, ef_construction=64) para busca coseno |
| [005](docs/adr/005-busca-hibrida.md) | Busca híbrida vetorial + BM25 com RRF |
| [006](docs/adr/006-controle-de-custo.md) | Cache de embedding + rate limit + métricas de token |
| [007](docs/adr/007-row-level-security.md) | Row Level Security no PostgreSQL para isolamento multiusuário |
| [008](docs/adr/008-estrategia-deploy-kubernetes.md) | Deploy em Kubernetes com Helm chart |
| [009](docs/adr/009-estrategia-resiliencia-ia.md) | Resilience4j: Circuit Breaker + Retry + fallback gracioso |

## Estrutura do projeto

```
rag-platform/
├── src/
│   ├── main/java/dev/ragplatform/
│   │   ├── domain/              # Núcleo — records, ports, exceptions (sem framework)
│   │   ├── application/usecase/ # Casos de uso: ChatService, SearchService, etc.
│   │   └── infrastructure/
│   │       ├── ai/              # Groq, Ollama, OpenAI, Fake, EmbeddingQueryCache
│   │       ├── async/           # IngestionListener (job de background)
│   │       ├── observability/   # AiMetrics (Micrometer)
│   │       ├── persistence/     # JPA entities, adapters, VectorJdbcRepository
│   │       ├── security/        # JWT, SecurityConfig, UserPrincipal
│   │       ├── web/             # Controllers, DTOs, RateLimitInterceptor
│   │       └── storage/         # LocalFileStorage
│   ├── main/resources/
│   │   ├── application.yml
│   │   └── db/migration/        # Flyway V1–V7
│   └── test/
│       ├── java/dev/ragplatform/ # *IT.java — Testcontainers
│       └── resources/golden/    # dataset.json (golden set de avaliação)
├── frontend/                    # React + Vite
│   ├── src/features/            # auth, documents, chat (feature-based)
│   ├── Dockerfile               # Node 22 build → nginx runtime
│   └── nginx.conf               # Proxy /api/ e /auth/ para backend, SSE config
├── docs/
│   └── adr/                     # 9 Architecture Decision Records (001–009)
├── k8s/helm/rag-platform/       # Helm chart: Deployment, Service, Ingress, HPA
├── e2e/                         # Playwright — testes E2E ponta a ponta
├── Dockerfile                   # Maven build → JRE 21 alpine, non-root user
├── docker-compose.yml           # postgres + redis + zipkin + app + frontend
└── .github/workflows/ci.yml     # CI: backend (verify) + frontend (tsc+build) + helm lint
```
