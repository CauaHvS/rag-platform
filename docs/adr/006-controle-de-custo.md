# ADR 006 - Controle de Custo e Rate Limit

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

A plataforma faz chamadas pagas a dois provedores de IA:
- **Groq (chat)**: custo por token de entrada + saída (llama-3.3-70b-versatile).
- **OpenAI (embedding em prod)**: $0,02 por milhão de tokens (text-embedding-3-small).

Sem controle, um usuário pode:
1. Fazer centenas de perguntas por minuto, gerando custo descontrolado.
2. Fazer upload de um documento de 100MB com texto denso, gerando milhares de
   chamadas de embedding.
3. Perguntar a mesma coisa repetidamente, gastando tokens desnecessários.

O sistema precisa de mecanismos que tornem o custo **observável**, **previsível** e
**limitado** sem degradar a experiência do usuário.

## Decisão

Implementar três mecanismos complementares:

### 1. Cache de embedding de consulta (Redis)

Perguntas repetidas ou similares fazem a mesma busca. O embedding da pergunta é
determinístico: a mesma string sempre gera o mesmo vetor. Cache-aside no Redis
antes de chamar o provedor.

**Chave**: `"emb:" + SHA-256(text)` (hex lowercase, 64 chars)
**Valor**: `float[]` serializado como bytes little-endian em Base64 (tamanho fixo
para 768 dimensões = 3072 bytes → 4096 chars Base64)
**TTL**: 3600 segundos (1 hora)

Escolha de SHA-256 em vez do texto direto: evita chaves longas no Redis, colisões
são criptograficamente improváveis, tamanho de chave sempre ≤ 70 chars.

Escolha de `StringRedisTemplate` direto em vez de `@Cacheable`:
`float[]` não é serializado corretamente pelo Jackson/JdkSerializationRedisSerializer
por padrão; serialização manual com `ByteBuffer` é explícita e controlável.

### 2. Rate limit por usuário (Redis INCR/EXPIRE)

Janela deslizante de 1 minuto por usuário autenticado nos endpoints de chat.

**Chave**: `"rate:chat:{userId}:{window}"` onde `window = currentTimeMillis() / 60000`
(janela de 1 minuto baseada em Unix epoch)
**Algoritmo**:
```
count = INCR key
if count == 1: EXPIRE key 120  # TTL 2× a janela para garantir expiração
if count > maxPerMinute: retornar 429
```
**Limite padrão**: 20 requests/minuto (configurável via `CHAT_RATE_LIMIT`)
**Resposta**: HTTP 429 com `ProblemDetail` (RFC 9457) e header `Retry-After: 60`

Implementação como `HandlerInterceptor` (não `Filter`): tem acesso ao
`Authentication` do SecurityContext após o Spring Security processar o request.
Registrado apenas em `/api/chat/**` via `WebMvcConfigurer`.

**Isolamento**: o contador é por userId (UUID), não por IP. Usuários distintos nunca
compartilham contador.

### 3. Métricas de tokens e custo (Micrometer)

Contadores expostos via `/actuator/metrics`:

| Métrica | Tipo | Descrição |
|---|---|---|
| `ai.chat.requests` | Counter | Total de requests de chat completados |
| `ai.chat.tokens.estimated` | Counter | Tokens estimados (question + answer) |
| `ai.embedding.requests` | Counter | Total de chamadas ao EmbeddingProvider |

**Estimativa de tokens**: `Math.max(1, text.length() / 4)` (heurística: ~4 chars
por token para português/inglês). Não é exata, mas serve para alertas e tendências.

Tags planejadas (não implementadas nesta fatia): `userId`, `provider` — permitiriam
custo por usuário e comparação entre provedores.

**Custo estimado com os dados atuais:**
- Chat Groq (llama-3.3-70b): ~$0.59/1M tokens input, ~$0.79/1M tokens output.
- Com prompt médio de 2000 tokens e resposta de 300 tokens:
  custo ≈ $0.00142 por pergunta.
- Limite de 20/min × 60min × 24h = 28.800 req/dia = ~$41/dia por usuário no pior caso.
  Rate limit é a principal proteção.

## Consequências

### Positivas
- Cache de embedding elimina custo de repetição: a pergunta mais frequente paga o
  provedor apenas uma vez por hora.
- Rate limit de 20/min é 3× mais que qualquer usuário normal consegue digitar;
  bloqueia scripts e loops acidentais.
- Métricas permitem detectar anomalias de custo sem acesso ao dashboard do provedor.
- Todos os mecanismos são implementados em Redis (já na stack) — sem dependência nova.
- Rate limit configurável por variável de ambiente permite ajuste por plano/tier
  de usuário sem redeploy (reinicialização necessária mas não rebuild).

### Negativas
- Estimativa de tokens por `length/4` é imprecisa para textos com caracteres
  multibyte (CJK, emoji) — pode subestimar em até 4×. Para auditoria de custo real,
  seria necessário integrar o contador de tokens da resposta do provedor.
- Cache de embedding por query exata: perguntas semanticamente iguais com palavras
  diferentes ("quanto custa?" vs "qual o preço?") são caches diferentes. Para
  cache semântico seria necessário ANN no próprio Redis (RedisSearch) ou arredondar
  o vetor — fora do escopo.
- Rate limit de janela fixa (não sliding) tem o problema do "double window": no
  pior caso, um usuário pode fazer 40 requests em 2 segundos (20 no final de uma
  janela + 20 no início da próxima). Janela deslizante real requer Redis Sorted Set
  com timestamps — custo de implementação maior; aceitável para o escopo.
- Não há controle de custo por documento (ingestão): um documento de 10MB pode
  gerar 6000+ chunks e 6000+ chamadas de embedding. Rate limit de ingestão é uma
  lacuna não endereçada nesta fatia.

## Alternativas consideradas

### Rate limit por IP
Rejeitado. Em ambientes corporativos, múltiplos usuários compartilham o mesmo IP
(NAT). Limitaria um departamento inteiro por abuso de um único usuário.

### Token bucket em vez de janela fixa
Considerado. Mais justo (permite burst curto, depois limita). Requer mais operações
Redis (GET + DECR + EXPIRE atômico via Lua script). Rejeitado pela simplicidade da
janela fixa para o escopo atual.

### Billing integrado (Stripe + medição de tokens)
Considerado para um produto real. Fora do escopo de portfólio — exige infraestrutura
de webhook, modelo de pricing e frontend de fatura.

### Tiered rate limits por plano
Considerado. Free tier (5/min), Pro (50/min), Enterprise (ilimitado). Requer tabela
de planos no banco e integração com o JWT (claim `plan`). Planejado como extensão
futura; configurável por env var já prepara o terreno.

## Referências
- Redis INCR/EXPIRE rate limiting: https://redis.io/docs/manual/patterns/rate-limiting/
- RFC 9457 Problem Details: https://www.rfc-editor.org/rfc/rfc9457
- Groq pricing: https://groq.com/pricing (consultado 2026-07-15)
- OpenAI text-embedding-3-small pricing: $0.02/1M tokens
- `EmbeddingQueryCache.java` — implementação do cache de embedding
- `RateLimitInterceptor.java` — implementação do rate limit
- `AiMetrics.java` — métricas Micrometer
- `RateLimitIT.java` — testes de integração do rate limit
- `MetricsIT.java` — testes de integração das métricas
