# ADR 009 - Estratégia de resiliência para chamadas de IA

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

A plataforma RAG depende de dois provedores externos de IA: Groq (chat) e Ollama
(embeddings). Ambas as dependências são síncronas no caminho crítico de resposta ao
usuário ou no pipeline de ingestão. Falhas, lentidão ou indisponibilidade desses
provedores se propagam diretamente para o usuário como erros 500, requests pendurados
indefinidamente ou ingestões que ficam travadas.

O CLAUDE.md define como não-negociável "degradação graciosa com mecanismo": não basta
try/catch; é preciso timeout, retry com backoff, circuit breaker e rate limit nas
chamadas de IA.

O projeto inicialmente usava `spring-retry` (`@Retryable`/`@Recover`) que cobre apenas
retry — sem circuit breaker, sem métricas nativas, sem controle fino de estado de
abertura. Também não havia timeouts HTTP configurados nos clientes REST.

Dois tipos de chamada têm comportamentos de degradação distintos:
- **Query de busca (`embedQuery`)**: tolerante à perda de vetores; a busca híbrida
  ainda funciona via BM25 (full-text). Degradação graciosa é desejável.
- **Ingestão (`embedDocuments`)**: intolerante; gravar vetor zero no banco produz dado
  corrompido permanentemente. Falha explícita é a resposta correta.

## Decisão

Adotar **Resilience4j** (`resilience4j-spring-boot3` 2.3.0) como biblioteca de
resiliência, substituindo `spring-retry` integralmente, com a seguinte estratégia:

**Circuit Breaker + Retry em camadas (anotações AOP):**
- `@Retry` (order=3, externo) → `@CircuitBreaker` (order=2, interno) → método.
- Quando o CB está aberto, lança `CallNotPermittedException`; o Retry está configurado
  para ignorar essa exceção (`ignoreExceptions`), evitando retentativas inúteis.
- O `fallbackMethod` fica na anotação `@Retry`, sendo invocado tanto após esgotar
  tentativas quanto no fast-fail do CB aberto.

**Configuração por provedor:**

| Parâmetro | groq-chat | ollama-embed |
|---|---|---|
| Sliding window | COUNT_BASED, 10 | COUNT_BASED, 5 |
| Failure rate threshold | 50% | 60% |
| Slow call threshold | 5s, 50% | — |
| Wait duration (open) | 30s | 20s |
| Half-open calls | 3 | 2 |
| Retry max attempts | 3 | 2 |
| Retry wait | 500ms × 2 (exp) | 500ms (linear) |

**Timeouts HTTP explícitos:**
- GroqChatProvider: connect=5s, read=10s (RestClient + HttpClient para streaming).
- OllamaEmbeddingProvider: connect=3s, read=5s (RestClient).

**Degradação graciosa diferenciada:**
- `embedQuery` fallback: retorna `new float[768]` (vetor zero). A busca híbrida
  continua via BM25; o componente vetorial é neutralizado sem erro para o usuário.
- `embedDocuments` / `embedDocument` fallback: lança `AiUnavailableException`. O job
  de ingestão marca o documento como `FAILED`, que é o estado correto e recuperável.
- `chat` / `stream` fallback: lança `AiUnavailableException` → HTTP 503 com
  ProblemDetail para o cliente.

**Observabilidade:**
- Actuator expõe `/actuator/circuitbreakers` e `/actuator/retries`.
- `registerHealthIndicator: true` inclui estado dos CBs em `/actuator/health`.

## Consequências

### Positivas
- Circuit breaker evita sobrecarga em cascata: quando Groq ou Ollama estão fora, o
  sistema para de tentar rapidamente em vez de acumular threads bloqueadas.
- Retry com backoff exponencial absorve falhas transitórias (rate limit 429, restart
  do Ollama) sem intervenção manual.
- Degradação da busca (vetor zero) é invisível para o usuário; a qualidade cai mas
  o sistema continua operacional.
- Timeouts HTTP impedem threads virtuais presas indefinidamente por conexão travada.
- Métricas de CB e retry são visíveis no Actuator e integráveis com Micrometer/Prometheus.

### Negativas
- Resilience4j requer Spring AOP; métodos chamados internamente na mesma classe
  (`this.embedDocument()` dentro de `embedQuery()`) não passam pelo proxy e não são
  protegidos pelas anotações — exige atenção ao revisar o código.
- A ordem dos aspectos (Retry externo, CB interno) é implícita e não óbvia; um
  desenvolvedor pode inverter acidentalmente e produzir comportamento errado.
- Backoff exponencial em 3 tentativas pode adicionar até ~1,5s de latência extra em
  falhas do Groq antes de chegar ao fallback.
- Streaming (`stream()`) usa apenas CB (sem retry), pois retentar uma stream é
  semanticamente problemático; isso significa que streams são mais frágeis que
  chamadas síncronas a falhas transitórias.
- O vetor zero retornado por `embedQueryFallback` pode reduzir significativamente a
  qualidade da busca sem sinalizar degradação ao usuário (trade-off intencional,
  mas implícito).

## Alternativas consideradas

### Manter spring-retry
Rejeitada: cobre apenas retry, sem circuit breaker. Não há mecanismo de fast-fail
quando o provedor está persistentemente fora, o que mantém o risco de thread starvation.

### Resiliência manual (try/catch + contador)
Rejeitada: reimplementar circuit breaker manualmente é complexo, propenso a bugs de
concorrência e não fornece métricas integradas ao Actuator/Micrometer.

### Spring Boot 4 nativo (@Retryable + @ConcurrencyLimit)
Considerada mas prematura: o projeto está no Boot 3.x; migrar para Boot 4 só para
obter essas anotações não é justificável agora. Resilience4j é a solução madura
para Boot 3.

### Bulkhead (ThreadPool ou Semaphore)
Considerada para limitar concorrência por provedor, mas adiada: com virtual threads
(Java 21), o custo de thread bloqueada é muito menor; o benefício do bulkhead é
reduzido neste cenário. Pode ser adicionado como camada extra se necessário.

### Rate limiter de saída (Resilience4j RateLimiter)
Adiado para ADR de controle de custo (ADR 006): o rate limit de entrada por usuário
já existe via bucket; o rate limit de saída para Groq (cota da API) seria uma camada
adicional, mas Groq já retorna 429 que o retry trata adequadamente.

## Referências
- [Resilience4j Spring Boot 3 docs](https://resilience4j.readme.io/docs/getting-started-3)
- ADR 002 — Provedores de IA (Groq + Ollama)
- ADR 006 — Controle de custo e rate limit
- `src/test/java/dev/ragplatform/AiResilienceTest.java` — testes unitários das premissas de CB e retry
