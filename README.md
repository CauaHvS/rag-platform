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
- Resiliência nas chamadas ao LLM: Spring Retry com backoff exponencial, circuit
  breaker via fallback, 503 com ProblemDetail (RFC 9457) em vez de NPE.
- Cache de embeddings de consulta no Redis (SHA-256 + float[] Base64 little-endian,
  TTL 1h) e rate limit por usuário (Redis INCR/EXPIRE, 429 com Retry-After).
- Observabilidade: métricas Micrometer (ai.chat.requests, ai.chat.tokens.estimated,
  ai.embedding.requests) expostas via `/actuator/metrics`.
- Streaming token a token via Server-Sent Events com virtual threads.
- Frontend React com upload + polling de status + chat com streaming + histórico.

## Stack

| Camada | Tecnologia |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven |
| Banco | PostgreSQL 16 + pgvector |
| Cache / Rate limit | Redis 7 |
| Embeddings (dev) | Ollama + nomic-embed-text-v1 (768 dim) |
| Embeddings (prod) | OpenAI text-embedding-3-small (dimensions=768) |
| Chat | Groq — llama-3.3-70b-versatile |
| Migrations | Flyway |
| Testes | JUnit 5 + Testcontainers + AssertJ |
| Frontend | React 19, Vite, TypeScript, Tailwind, TanStack Query/Router |
| Infra | Docker, Docker Compose, GitHub Actions |

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
| `JWT_SECRET` | Chave HMAC-SHA256 para JWT (base64, 32 bytes) | **Obrigatório** |
| `GROQ_API_KEY` | Chave da API Groq | **Obrigatório** |
| `EMBEDDING_PROVIDER` | `ollama` ou `openai` | `ollama` |
| `OPENAI_API_KEY` | Chave OpenAI (se EMBEDDING_PROVIDER=openai) | — |
| `EMBEDDING_BASE_URL` | URL base do Ollama | `http://localhost:11434` |
| `DATABASE_URL` | JDBC URL do PostgreSQL | `jdbc:postgresql://localhost:5432/ragplatform` |
| `DATABASE_USERNAME` | Usuário do banco | `ragplatform` |
| `DATABASE_PASSWORD` | Senha do banco | `ragplatform` |
| `REDIS_HOST` | Host do Redis | `localhost` |
| `REDIS_PORT` | Porta do Redis | `6379` |
| `STORAGE_LOCAL_PATH` | Diretório de uploads | `./uploads` |
| `CHAT_RATE_LIMIT` | Max requests de chat por minuto por usuário | `20` |

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

## Avaliação de qualidade

```bash
# Rodar avaliação contra golden set
./mvnw verify -Dit.test="EvaluationIT" -Dlogging.level.dev.ragplatform=INFO
```

Resultados atuais (modo híbrido, golden set com 15 perguntas sobre 3 documentos):
- Recall@5: 1.0 (todas as perguntas encontraram o documento correto no top-5)
- MRR: 0.667 (posição média da primeira resposta correta)
- Hybrid Recall >= Vector Recall: confirmado

## Decisões de arquitetura (ADRs)

| ADR | Decisão |
|---|---|
| [001](docs/adr/001-arquitetura-hexagonal.md) | Arquitetura Hexagonal (Ports and Adapters) |
| [002](docs/adr/002-provedores-ia.md) | ChatProvider=Groq, EmbeddingProvider=Ollama/OpenAI |
| [003](docs/adr/003-estrategia-chunking.md) | Chunking por janela deslizante (1500 chars, overlap 200) |
| [004](docs/adr/004-indice-pgvector.md) | Índice HNSW (m=16, ef_construction=64) para busca coseno |
| [005](docs/adr/005-busca-hibrida.md) | Busca híbrida vetorial + BM25 com RRF |
| [006](docs/adr/006-controle-de-custo.md) | Cache de embedding + rate limit + métricas de token |

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
│   ├── adr/                     # Architecture Decision Records
│   └── api/openapi.yml          # Spec OpenAPI 3.1 completa
├── Dockerfile                   # Maven build → JRE 21 alpine, non-root user
├── docker-compose.yml           # postgres + redis + app + frontend
└── .github/workflows/ci.yml     # CI: backend (verify) + frontend (build + tsc)
```
