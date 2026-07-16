# Roadmap de Fatias — RAG Platform

Full stack, fatias verticais, contract-first. Cada fatia tem o prompt para colar no
Claude Code, o critério de validação e termina em commit.

## Estado atual — Projeto concluído ✓

| Fase | Entregável | Status |
|---|---|---|
| 0 — Fundação | ADRs 001/002, esqueleto Spring Boot + pgvector, frontend + mockups | ✓ Concluído |
| 1 — Auth | JWT stateless, rotas protegidas, login/registro ponta a ponta | ✓ Concluído |
| 2 — Ingestão | Upload + máquina de estados, extração texto/OCR, chunking, embeddings batch | ✓ Concluído |
| 3 — Recuperação | Busca vetorial + isolamento multiusuário, busca híbrida BM25+RRF | ✓ Concluído |
| 4 — Chat RAG | Pipeline completo com fontes, streaming SSE, tela de chat | ✓ Concluído |
| 5 — Avaliação | Golden set + Recall@5/MRR (EvaluationIT), groundedness LLM-as-judge | ✓ Concluído |
| 6 — Gestão/Custo | Histórico, exclusão com cascata, tokens reais + custo USD, hit/miss cache | ✓ Concluído |
| 7 — Empacotamento | Docker multi-stage + compose, CI GitHub Actions, E2E Playwright | ✓ Concluído |
| 8 — Documentação | README completo, 9 ADRs, Swagger, métricas de observabilidade | ✓ Concluído |

**Resiliência (ADR 009):** Resilience4j — Circuit Breaker + Retry + fallback gracioso
diferenciado (embed de query → vetor zero; embed de ingestão → FAILED).

**Avaliação quantitativa:** Recall@5 ≥ 0.80, MRR ≥ 0.60 como gate do CI.
Groundedness ≥ 80% (LLM-as-judge, execução manual com GROQ_API_KEY).

---

Diferenciais do projeto (onde o roadmap é denso): **ingestão assíncrona resiliente
(Fase 2)**, **recuperação híbrida com rerank (Fase 3)**, **avaliação com métricas
(Fase 5)** e **isolamento multiusuário provado por teste (Fase 3)**.

---

# Fase 0 — Fundação e Decisões

## Fatia 0.1 — Provedores de IA (ADR)
**Prompt:**
```
Iniciar o rag-platform.

Decisão dos provedores. Fato já apurado: o Groq NÃO serve modelos de embedding (a
API cobre chat completions, audio e batch; o SDK expõe embeddings.create apenas por
compatibilidade OpenAI, sem modelo servido). Use version-troubleshooting para
revalidar na doc OFICIAL do Groq (lista de modelos) e confirmar. Traga a fonte.

Architect cria o ADR 002 (provedores): ChatProvider = Groq; EmbeddingProvider =
avaliar OpenAI text-embedding-3-small, Cohere, Gemini e Ollama local
(nomic-embed-text). Compare custo, dimensão do vetor, qualidade e dependência de
rede. Recomende Ollama em dev + provedor de API em prod, atrás da MESMA porta.
Registre a dimensão escolhida: ela define a coluna vector(N) e trocar depois exige
reindexar tudo.

Nenhuma implementação.
```
**Valida:** ADR 002 com fonte oficial e dimensão do vetor definida. **Commit**

## Fatia 0.2 — Arquitetura e esqueleto
**Prompt:**
```
Architect define a estrutura hexagonal e o ADR 001. As portas de saída
EmbeddingProvider e ChatProvider ficam no domínio; os adapters (Groq, OpenAI/Ollama)
na infra. Cada porta tem um adapter fake para teste sem rede nem cota.

Backend-engineer cria o esqueleto Spring Boot 3 (Java 21 + Maven): sobe, PostgreSQL
com extensão pgvector habilitada via Flyway, Redis conectado, health check. Segredos
por env var (secrets-management); nenhuma chave no código.

Valide que compila, sobe, e a extensão vector existe no banco.
```
**Valida:** app sobe, `CREATE EXTENSION vector` aplicada, health verde. **Commit**

## Fatia 0.3 — Frontend + mockups
**Prompt:**
```
Frontend-engineer cria o app React + TS + Vite: Tailwind, TanStack Query, router,
RHF + Zod, Axios com interceptor, ErrorBoundary, skeleton, dark mode. Consome /health.

Depois, ui-design: design system + mockups HTML das telas (Login, Meus Documentos com
status de ingestão, Upload, Chat com fontes, Histórico). Nenhuma lógica.
```
**Valida:** front sobe; mockups aprovados. **Commit**

---

# Fase 1 — Autenticação (base do isolamento)

## Fatia 1.1 — Auth JWT ponta a ponta
**Prompt:**
```
auth-security: registro, login, JWT stateless, rotas protegidas, CORS para o
frontend. input-validation nos DTOs; erros como ProblemDetail (RFC 9457).
Contract-first: contrato antes dos dois lados.

Frontend seguindo o mockup: Login/Cadastro (RHF+Zod), guarda de rota, client HTTP
centralizado injetando o token e tratando 401 globalmente. api-client-typegen gera o
client tipado do OpenAPI.

integration-testing: fluxo feliz + credencial inválida + rota protegida sem token.
```
**Valida:** login ponta a ponta; rota protegida barra sem token. **Commit**

---

# Fase 2 — Ingestão Assíncrona (não subestime esta fase)

> Por que assíncrona: upload + OCR + chunking + N chamadas de embedding num único
> request HTTP estoura timeout em qualquer PDF real. Upload retorna 202 e o trabalho
> roda em background, com status consultável.

## Fatia 2.1 — Upload e máquina de estados
**Prompt:**
```
Architect define o agregado Document com máquina de estados: PENDING > EXTRACTING >
CHUNKING > EMBEDDING > READY | FAILED (com mensagem de erro). Contrato:
POST /documents (multipart) retorna 202 + documentId + status;
GET /documents/{id} retorna o status; GET /documents lista os do usuário.

Backend: persistir o documento e o arquivo, vinculado ao usuário dono, e enfileirar o
processamento. Nenhuma extração ainda: o job só transiciona o estado.

unit-testing das transições (transição inválida deve falhar).
```
**Valida:** upload retorna 202, status consultável, estados transicionam. **Commit**

## Fatia 2.2 — Extração de texto + OCR
**Prompt:**
```
background-jobs: o worker extrai texto do PDF (PDFBox ou Apache Tika). Se o PDF for
escaneado (pouco ou nenhum texto extraível), cai para OCR (Tesseract via tess4j).
Detecte o caso automaticamente; não rode OCR à toa (é caro e lento).
O Tesseract e os dados de idioma (por, eng) precisam estar no container: alinhe com
devsecops agora, não na Fase 7.

Estado vai para CHUNKING ao concluir; FAILED com motivo claro se o arquivo for
inválido/corrompido/protegido.

integration-testing com um PDF de texto e um PDF escaneado (fixtures no repo).
```
**Valida:** ambos os PDFs geram texto; PDF inválido vira FAILED com motivo. **Commit**

## Fatia 2.3 — Chunking (ADR)
**Prompt:**
```
Architect cria o ADR 003 (estratégia de chunking): compare chunk fixo por caracteres,
por tokens, e recursivo respeitando fronteira semântica (parágrafo/sentença). Discuta
tamanho e overlap com o trade-off real: chunk grande dá contexto mas dilui o sinal do
embedding e encarece o prompt; chunk pequeno é preciso mas fragmenta a resposta.
Recomende um ponto de partida (ex: ~500 tokens, overlap ~15%) e deixe PARAMETRIZÁVEL,
porque a Fase 5 vai medir e ajustar isso com dado.

Backend implementa o chunker escolhido. Cada chunk guarda: texto, ordem, e a posição
de origem (página/offset) para citar a fonte depois. unit-testing do chunker
(fronteiras, overlap, texto vazio, texto menor que um chunk).
```
**Valida:** ADR 003; chunks persistidos com posição de origem. **Commit**

## Fatia 2.4 — Tela de documentos com status
**Prompt:**
```
Frontend seguindo o mockup: upload com feedback, lista "Meus Documentos" com o status
de ingestão (polling ou SSE), estados de loading/erro/vazio, e a exibição do motivo
quando FAILED. frontend-testing do fluxo.
```
**Valida:** upload e status visíveis ponta a ponta. **Commit**

---

# Fase 3 — Recuperação (o coração)

## Fatia 3.1 — Embeddings + índice vetorial (ADR)
**Prompt:**
```
rag-pipeline. Implementar o EmbeddingProvider (adapter do provedor do ADR 002) e o
adapter fake para testes.

O worker (estado EMBEDDING) gera embeddings dos chunks EM LOTE (não um request por
chunk) e salva como vector(N) no PgVector. Idempotente e retomável: reprocessar não
duplica; falha no meio retoma dos chunks pendentes, não do zero. Estado vai a READY.

resilience nas chamadas ao provedor: timeout, retry com backoff exponencial, circuit
breaker. caching do embedding (mesmo texto = mesmo vetor, não pague duas vezes).

Architect cria o ADR 004 (índice do PgVector): compare HNSW e IVFFlat (recall vs
tempo de build vs memória) e nenhum índice (scan exato, ok em volume pequeno).
Recomende HNSW para leitura e explique o custo de build.

integration-testing: ingestão completa de um PDF termina em READY com vetores no banco.
Teste de retomada: matar o job no meio e reprocessar não duplica chunks.
```
**Valida:** documento chega a READY; vetores no banco; retomada não duplica. **Commit**

## Fatia 3.2 — Busca semântica + ISOLAMENTO
**Prompt:**
```
Endpoint de busca: recebe a pergunta, gera o embedding, retorna os top-K chunks por
distância de cosseno, com score e documento de origem.

ISOLAMENTO (requisito de segurança): o filtro por usuário dono vai NA QUERY SQL, não
em memória depois de recuperar. Avalie Row Level Security no Postgres como defesa em
profundidade e registre em ADR se adotar.

test-engineer:
- teste que prova busca semântica de verdade: pergunta com palavras DIFERENTES do
  documento recupera o trecho certo (senão você só provou busca por palavra-chave);
- teste que PROVA o isolamento: usuário A não recupera NENHUM chunk de B, em toda
  operação de busca. Este teste é obrigatório e não pode ser removido.
```
**Valida:** busca por significado funciona; teste de isolamento verde. **Commit**

## Fatia 3.3 — Busca híbrida + rerank
**Prompt:**
```
Busca vetorial pura erra em termos exatos (códigos, siglas, nomes próprios, números).
Implementar busca HÍBRIDA: full-text do Postgres (tsvector/BM25) + busca vetorial,
fundindo os resultados (Reciprocal Rank Fusion) e depois reranqueando o top-N.

Rerank: comece por um cross-encoder leve ou um rerank via LLM sobre poucos candidatos.
Se o custo não compensar, documente e mantenha só a fusão. A decisão é medida na
Fase 5, não no achismo.

Manter a busca vetorial pura acessível por flag, para comparar as duas na avaliação.

Testes: consulta com termo exato (código/sigla) que a busca vetorial pura erra e a
híbrida acerta.
```
**Valida:** híbrida acerta o caso de termo exato; flag permite comparar. **Commit**

---

# Fase 4 — Geração (Chat RAG)

## Fatia 4.1 — Chat com fontes
**Prompt:**
```
Implementar o ChatProvider (Groq) + adapter fake.

Fluxo: pergunta > recuperação (Fase 3) > montagem do prompt com os chunks como
contexto > LLM gera > resposta COM as fontes (documento, página/offset, trecho).

Prompt como código: versionado, em arquivo, testável. O system prompt DEVE instruir a
responder apenas com base no contexto e a dizer que não sabe quando o contexto não
cobre a pergunta. Teste de regressão do prompt: pergunta fora do escopo dos documentos
deve receber "não sei", não alucinação.

Orçamento de contexto: trunque/selecione chunks para caber no limite de tokens; nunca
estoure o contexto do modelo silenciosamente.

resilience: timeout, retry, circuit breaker. Se o LLM cair, erro claro e a busca
continua funcionando (degradação graciosa de verdade).
```
**Valida:** pergunta sobre um PDF retorna resposta correta com fontes; pergunta fora do escopo retorna "não sei". **Commit**

## Fatia 4.2 — Streaming (SSE)
**Prompt:**
```
A resposta do LLM demora. Implementar streaming de tokens via SSE do backend ao
frontend. As fontes são enviadas ao final (ou no início, se recuperadas antes).
Tratar cancelamento pelo usuário (abortar a chamada, não vazar conexão).
```
**Valida:** resposta aparece token a token; cancelar interrompe de fato. **Commit**

## Fatia 4.3 — Tela de chat
**Prompt:**
```
Frontend seguindo o mockup: chat com streaming, exibição das fontes clicáveis (abre o
trecho no documento), estados de loading/erro/vazio, e mensagem clara quando a IA
está indisponível. frontend-accessibility (foco, leitura de tela na resposta que
chega em stream).
```
**Valida:** chat completo ponta a ponta. **Commit**

---

# Fase 5 — Avaliação (o que separa plataforma de demo) ⭐

> Sem isto, "a busca funciona" é opinião. Esta fase é o maior diferencial do projeto
> e a pergunta que um entrevistador sênior vai fazer.

## Fatia 5.1 — Golden set e métricas de recuperação
**Prompt:**
```
Construir um golden set versionado no repo: um conjunto de documentos de teste e
~20-30 perguntas, cada uma com o(s) chunk(s)/trecho(s) que deveriam ser recuperados.

Implementar um harness de avaliação (roda em teste ou via comando) que mede:
- recall@k e MRR (a recuperação traz o trecho certo entre os k primeiros?)
- latência p95 da recuperação

Rodar o harness comparando as configurações: vetorial pura vs híbrida vs híbrida+rerank;
e ao menos duas configurações de chunking (tamanho/overlap).

Entregar uma TABELA com os números e uma recomendação fundamentada. Ajustar os
defaults do sistema com base no resultado, e registrar em ADR.
```
**Valida:** tabela de métricas; defaults ajustados por dado, não achismo. **Commit**

## Fatia 5.2 — Groundedness da resposta
**Prompt:**
```
Medir se a resposta gerada é fundamentada nas fontes recuperadas (groundedness) e se
responde a pergunta (relevance). Use LLM-as-judge com um prompt de avaliação próprio,
sobre o golden set. Reportar a taxa de alucinação detectada.

Honestidade: LLM-as-judge é ruidoso. Documente a limitação e não venda o número como
verdade absoluta. Vale mais como regressão (piorou/melhorou) do que como nota absoluta.
```
**Valida:** relatório de groundedness com limitações documentadas. **Commit**

---

# Fase 6 — Histórico, Custo e Observabilidade

## Fatia 6.1 — Histórico e gestão de documentos
**Prompt:**
```
Persistir conversas por usuário (histórico consultável). Exclusão de documento
removendo chunks e vetores em cascata. Confirmar o isolamento em TODAS as operações
(listar, buscar, excluir, histórico), com teste.
```
**Valida:** histórico persiste; exclusão limpa vetores; isolamento em toda operação. **Commit**

## Fatia 6.2 — Custo e observabilidade
**Prompt:**
```
observability: métricas de negócio (documentos ingeridos, tempo de ingestão, buscas,
latência p95 da recuperação e da geração), logs estruturados correlacionados, e
tracing distribuído da pipeline (upload > extração > chunk > embedding > busca >
LLM), para achar onde o tempo se vai.

CUSTO: contabilize tokens de entrada/saída e custo estimado por request e por usuário,
exposto como métrica. Rate limit por usuário (resilience) para conter abuso e conta
surpresa. Dashboard mostrando custo acumulado.
```
**Valida:** trace da pipeline ponta a ponta; métrica de custo/token visível. **Commit**

---

# Fase 7 — Empacotamento e CI

## Fatia 7.1 — Docker
**Prompt:**
```
containerization: Dockerfile multi-stage do backend (com Tesseract + dados de idioma
por/eng instalados), frontend buildado e servido por Nginx, compose subindo Postgres
com pgvector, Redis, backend e frontend. API keys por env var; nunca no repo.
Valide docker compose up ponta a ponta, incluindo o OCR dentro do container.
```
**Valida:** stack sobe; OCR funciona no container. **Commit**

## Fatia 7.2 — CI
**Prompt:**
```
cicd: build backend + frontend, unit + integração (Testcontainers) + lint. Os testes
de IA usam os adapters fake (sem rede, sem cota). O harness de avaliação roda como job
separado (opcional/manual, pois consome cota).
```
**Valida:** CI verde sem gastar cota de IA. **Commit**

## Fatia 7.3 — E2E
**Prompt:**
```
e2e-testing (Playwright): fluxo crítico ponta a ponta contra a stack do compose:
cadastro > login > upload de PDF > aguardar READY > perguntar > ver resposta com
fontes. Inclui o caminho de erro (documento inválido).
```
**Valida:** E2E verde. **Commit**

---

# Fase 8 — Documentação

## Fatia 8.1 — README, diagramas, Swagger
**Prompt:**
```
docs-writer: README profissional explicando o que é RAG e por que não perguntar direto
ao LLM; diagrama da pipeline de ingestão e da pipeline de consulta (Mermaid); como
rodar; como configurar as chaves; RESULTADOS DA AVALIAÇÃO (a tabela da Fase 5, é o
diferencial, coloque em destaque); todos os ADRs em docs/adr; Swagger acessível.
```
**Valida:** README completo com métricas; Swagger no ar. **Commit + Push**

---

# Fluxo de Commits
```
0.1 ADR provedores      0.2 Hexagonal + PgVector    0.3 Front + mockups
1.1 Auth JWT
2.1 Upload + estados    2.2 Extração + OCR          2.3 Chunking (ADR)   2.4 Tela documentos
3.1 Embeddings + índice 3.2 Busca + ISOLAMENTO ⭐   3.3 Híbrida + rerank
4.1 Chat com fontes     4.2 Streaming SSE           4.3 Tela de chat
5.1 Golden set + métricas ⭐   5.2 Groundedness
6.1 Histórico           6.2 Custo + observabilidade
7.1 Docker (OCR)        7.2 CI                      7.3 E2E
8.1 README + diagramas + Swagger
```

# O que o projeto prova em entrevista
- "O que é RAG e por que não perguntar direto ao LLM?"
- "Qual sua estratégia de chunking e por quê?" (com ADR e **número**, não opinião)
- "Como você sabe que a recuperação está boa?" (**golden set, recall@k, MRR**)
- "Por que busca híbrida e não só vetorial?" (caso do termo exato)
- "Como garante que um usuário não acessa documento de outro?" (filtro na query + teste)
- "Como controla custo e latência de IA?" (batch, cache, rate limit, métrica de token)
- "O que acontece se o provedor de IA cair?" (circuit breaker, degradação graciosa)
- "Como ingere um PDF de 500 páginas sem estourar timeout?" (pipeline assíncrona idempotente)

# Dicas de operação
- Cota de IA: use os adapters fake nos testes e Ollama local em dev. Só gaste cota de
  API no que é real. Nunca deixe o CI queimando cota.
- Chave de API por env var desde o dia 1. Nunca commite chave.
- A dimensão do vetor (vector(N)) trava a coluna: mudar de modelo de embedding depois
  exige reindexar tudo. Decida com atenção na Fase 0.
- Commite ao fim de cada fatia. Contexto limpo entre fatias.
- Cole o resultado real; não presuma que funcionou.
