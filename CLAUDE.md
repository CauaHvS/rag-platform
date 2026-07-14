# CLAUDE.md — RAG Platform

## Identidade
- Nome: rag-platform
- Domínio: plataforma de perguntas e respostas sobre documentos privados (RAG)
- Stack: Java 21, Spring Boot 3, PostgreSQL + PgVector, Flyway, Redis, Docker, Testcontainers
- Frontend: React + TypeScript + Vite + Tailwind + TanStack Query + RHF/Zod
- IA: Groq (ChatProvider). EmbeddingProvider separado (ver abaixo).
- Objetivo: portfólio backend sênior. Prova IA aplicada com engenharia de verdade,
  não apenas chamar uma API de LLM.

## O que este projeto precisa provar
Sistema RAG completo e **avaliável**: ingestão assíncrona (OCR, chunking, embeddings)
resiliente e idempotente; recuperação híbrida (vetorial + full-text) com rerank;
geração fundamentada com citação de fontes; isolamento multiusuário; controle de
custo; e **métricas de qualidade de recuperação**, não apenas "parece que funciona".

## Decisão de provedores (RESOLVIDA, formalize no ADR 002)
Groq **não serve modelos de embedding**. A API dele cobre chat completions, audio e
batch (só /v1/chat/completions); os modelos servidos são de geração de texto. O SDK
do Groq expõe `embeddings.create` por compatibilidade OpenAI, mas isso é scaffolding,
não modelo servido. Não confie no SDK; confie na lista de modelos.

Portanto:
- **ChatProvider = Groq** (rápido e barato para geração).
- **EmbeddingProvider = outro provedor.** Avalie no ADR 002: OpenAI
  (text-embedding-3-small, barato e bom), Cohere, Gemini, ou local via Ollama
  (nomic-embed-text, custo zero, ótimo para dev e para não gastar cota).
  Recomendação: **Ollama local em dev + um provedor de API em prod**, atrás da mesma porta.

**Duas portas separadas (EmbeddingProvider e ChatProvider) são obrigatórias**, não
opcionais. Trocar de provedor não pode custar refatoração. Ambas com implementação
fake/stub para teste sem gastar cota nem depender de rede.

> Antes de codar, revalide os modelos e preços na doc oficial (version-troubleshooting).
> Preço e catálogo de modelos de IA mudam rápido.

## Princípios não negociáveis deste projeto

### Isolamento multiusuário (segurança, não feature)
A busca SÓ retorna chunks do dono. Nunca vaze contexto entre usuários. O filtro por
owner acontece **na query SQL**, não em memória depois de recuperar. Teste dedicado
prova que usuário A não recupera nada de B. Considere Row Level Security no Postgres
como defesa em profundidade (ADR).

### Ingestão é assíncrona
Upload retorna 202 + documentId + status. OCR, chunking e embeddings rodam em job de
background. O documento tem máquina de estados (PENDING > EXTRACTING > CHUNKING >
EMBEDDING > READY | FAILED) consultável pelo usuário. O job é **idempotente e
retomável**: reprocessar não duplica chunks; falhar no chunk 300 de 500 não refaz os
299 primeiros. Embedding em lote, não um request por chunk.

### Qualidade é medida, não presumida
Existe um golden set (perguntas + trechos corretos) e métricas de recuperação
(recall@k, MRR). Toda mudança em chunking, modelo de embedding, k ou rerank é
avaliada contra o golden set. "Melhorou" é afirmação com número.

### IA no backend
Chave de API e prompt vivem no backend. O front só manda a pergunta. Prompt como
código: versionado, testável, com teste de regressão.

### Degradação graciosa com mecanismo
Não basta try/catch. Use a skill `resilience`: timeout, retry com backoff,
circuit breaker e rate limit nas chamadas ao LLM e ao provedor de embeddings.
Se a IA cai, o sistema informa com clareza e a busca ainda funciona.

### Custo é observável
Contabilize tokens e custo estimado por request e por usuário, exposto como métrica.
Cache de embedding de pergunta repetida (caching). Rate limit por usuário.

## Como você (Claude) deve trabalhar aqui

### Idioma e tom
Português brasileiro, conciso e direto. Sem em-dashes. Honestidade técnica: aponte
problemas, riscos e trade-offs; discorde com fundamento; nunca concorde só pra agradar.

### Método
- Conceito e raciocínio antes do código.
- Fatias verticais ponta a ponta (UI > API > banco > volta), validadas antes da próxima.
- Contract-first: contrato da API antes de backend e frontend. Mockup (ui-design)
  antes de codar tela.
- Valide cada etapa. O humano cola o resultado real (log, erro, response); você
  confirma antes de seguir. Nunca presuma sucesso sem ver a saída.
- Erro: leia o log de baixo pra cima, isole a causa raiz, distinga sintoma de causa,
  explique antes de corrigir. Teste flaky = estado compartilhado; torne determinístico.

### Decisões
Trade-offs com prós/contras + recomendação; o humano decide. ADR obrigatório para:
provedores de IA, estratégia de chunking, índice do PgVector (HNSW vs IVFFlat),
estratégia de recuperação (vetorial puro vs híbrida + rerank), modelo de isolamento,
controle de custo.

### Qualidade de código
Identificadores e contratos (JSON, enums) em inglês. Mensagens, logs e comentários em
português. ADRs e documentação em português. Domínio sem framework. Config por env
var com fallback; API key NUNCA no código nem no repo. Testes e casos de borda desde
o início.

### Definição de "pronto"
Testes (unit + integração com Testcontainers + isolamento + avaliação), Docker
(compose com Postgres/PgVector + Redis), CI, segurança (auth + isolamento + segredos),
observabilidade (incluindo custo/tokens), documentação (README + ADRs + Swagger).

## Orquestração com subagentes
- **architect** — hexagonal, as portas de IA, ADRs, contratos, pipeline de ingestão.
- **backend-engineer** — ingestão, embeddings, busca híbrida, chat, streaming.
- **frontend-engineer** — upload com status, chat com streaming, fontes, histórico.
- **test-engineer** — testes, com destaque para isolamento e avaliação de recuperação.
- **code-reviewer** — revisão (atenção a vazamento de contexto entre usuários e a API keys).
- **devsecops** — Docker (Postgres com extensão vector, OCR no container), CI, segredos.
- **docs-writer** — README, ADRs, diagrama da pipeline.

## Skills deste projeto
- Núcleo IA: **rag-pipeline** (principal)
- Arquitetura: hexagonal-architecture, domain-driven-design, adr-writer, version-troubleshooting
- API/borda: api-design, input-validation, object-mapping
- Dados: database-migrations, caching
- Assíncrono/resiliência: **background-jobs** (ingestão), **resilience** (chamadas de IA),
  messaging-patterns (se usar fila)
- Segurança: auth-security, secrets-management
- Frontend: ui-design, react-frontend, api-client-typegen, frontend-accessibility
- Testes: unit-testing, integration-testing, frontend-testing, e2e-testing, performance-testing
- Infra/obs: containerization, cicd, observability

## Comandos
```
./mvnw clean verify
docker compose up -d --build
docker compose up -d postgres redis    # só infra em dev
cd frontend && npm run dev
```
