# ADR 002 - Provedores de IA (ChatProvider e EmbeddingProvider)

**Status:** Aceito
**Data:** 2026-07-14

## Contexto

O sistema RAG precisa de dois tipos de chamada a modelos de IA com características
diferentes:

1. **Geração de texto (chat):** recebe um prompt com contexto e gera uma resposta em
   linguagem natural. Latência é perceptível pelo usuário; throughput alto ajuda.

2. **Embeddings:** converte texto em vetor numérico para busca semântica. Chamadas
   frequentes (um batch por documento ingerido, uma por pergunta); custo por token e
   dimensão do vetor afetam diretamente custo de armazenamento e qualidade de busca.

A escolha inicial foi usar Groq para ambos os casos. Antes de implementar, foi
necessário validar se o Groq serve modelos de embedding.

**Validação na documentação oficial do Groq** (fonte: https://console.groq.com/docs/models,
consultado em 2026-07-14): os modelos disponíveis são classificados em Chat/Texto
(Llama 3.1 8B, Llama 3.3 70B, GPT OSS 120B, GPT OSS 20B), Áudio (Whisper Large V3,
Whisper Large V3 Turbo) e Agentes (Groq Compound, Groq Compound Mini). **Nenhum
modelo de embedding está listado.** O SDK do Groq expõe `embeddings.create` por
compatibilidade com a interface OpenAI, mas isso é scaffolding sem modelo servido;
não deve ser usado.

**Consequência direta:** Groq é adequado apenas como ChatProvider. Um segundo
provedor é obrigatório para embeddings.

**Restrições adicionais:**
- A dimensão do vetor escolhida define a coluna `vector(N)` no PgVector. Trocar de
  modelo de embedding depois exige reindexar todos os chunks existentes.
- Em desenvolvimento, gastar cota de API de embedding em cada teste é inaceitável;
  um modelo local é necessário.
- A troca de provedor de embedding (dev vs prod, ou migração futura) não pode exigir
  refatoração do domínio.

## Decisão

**ChatProvider = Groq** com o modelo `llama-3.3-70b-versatile` (ou equivalente
disponível na conta).

**EmbeddingProvider = Ollama (nomic-embed-text-v1) em desenvolvimento +
OpenAI (text-embedding-3-small com `dimensions=768`) em produção**, ambos atrás da
mesma interface `EmbeddingProvider` no domínio.

**Dimensão do vetor: 768.**

Justificativa da dimensão:
- `nomic-embed-text-v1` (Ollama) produz 768 dimensões nativamente, com janela de
  contexto de 8192 tokens. Fonte: https://huggingface.co/nomic-ai/nomic-embed-text-v1
- `text-embedding-3-small` (OpenAI) produz 1536 dimensões por padrão, mas suporta o
  parâmetro `dimensions` via Matryoshka Representation Learning para reduzir para 768
  sem retreinar. Fonte: https://openai.com/index/new-embedding-models-and-api-updates/
- 768 dimensões é suficiente para RAG em documentos privados e mantém o schema
  identico entre dev e prod, eliminando divergências.

**Configuração por variável de ambiente:**
```
EMBEDDING_PROVIDER=ollama|openai
EMBEDDING_BASE_URL=http://localhost:11434   # Ollama local
OPENAI_API_KEY=...                          # só em prod, nunca no repo
CHAT_PROVIDER=groq
GROQ_API_KEY=...
```

**Adapter fake obrigatório** para testes: retorna vetores de zeros em 768 dimensões,
sem rede, sem cota.

## Consequências

### Positivas
- Groq oferece throughput muito alto (280-1000 t/s) e latência baixa para geração,
  melhorando a experiência de streaming.
- Ollama elimina custo e dependência de rede em dev; testes de integração rodam sem
  internet e sem gastar cota.
- Schema de banco identico entre dev e prod (ambos com `vector(768)`); sem surpresas
  ao promover para produção.
- `text-embedding-3-small` com `dimensions=768` custa $0,02 por milhão de tokens,
  tornando o custo de prod previsivel e baixo.
- Trocar de provedor exige apenas um novo adapter; o domínio e o banco não mudam.
- `nomic-embed-text-v1` exige prefixos de instrução (`search_document:` para chunks,
  `search_query:` para perguntas), o que melhora a qualidade da busca.

### Negativas
- Dois provedores para gerenciar em vez de um: Groq para chat, Ollama/OpenAI para
  embedding. Requer documentação clara no onboarding.
- Ollama precisa estar rodando localmente em dev (processo separado ou via Docker
  Compose). Adiciona um pré-requisito de setup.
- A redução de dimensão do OpenAI via Matryoshka tem perda marginal de qualidade
  comparado ao vetor completo de 1536 dimensões. Para RAG em documentos privados,
  essa perda é aceitável.
- Se no futuro o modelo de embedding for trocado por um que não suporte 768
  dimensões, todos os chunks precisam ser re-embeddados. A escolha de 768 é uma
  aposta no modelo atual.

## Alternativas consideradas

### Groq para embeddings
Rejeitada. Groq não serve modelos de embedding. O SDK expõe a rota por compatibilidade
de interface mas não há modelo disponivel. Confirmado na lista oficial de modelos em
2026-07-14.

### OpenAI text-embedding-3-small em 1536 dimensões (padrão)
Rejeitada como dimensão padrão. nomic-embed-text-v1 produz 768 dimensões; usar 1536
em prod e 768 em dev criaria schemas diferentes entre ambientes. Com o parâmetro
`dimensions=768` o OpenAI entrega o mesmo schema com perda mínima de qualidade.

### Cohere embed-v3
Considerada. Produz 1024 dimensões, boa qualidade. Rejeitada porque: (a) não há
equivalente local gratuito com a mesma dimensão, criando o problema de schema
divergente; (b) custo maior que text-embedding-3-small; (c) adiciona um terceiro
provedor sem beneficio claro para o escopo do projeto.

### Gemini text-embedding-004
Considerada. Produz 768 dimensões por padrão (configuravel ate 3072). Tem um tier
gratuito. Rejeitada porque o SDK e a integração com Spring AI são menos maduros que
OpenAI, e o tier gratuito tem limites de RPM que podem interferir em testes de carga.

### Usar apenas Ollama (dev e prod)
Considerada como opção de zero custo. Rejeitada para prod porque exige infraestrutura
de GPU ou aceita latencia alta de CPU, adiciona complexidade operacional, e elimina
a demonstração de integração com provedor de API real, o que é relevante para
portfólio.

### nomic-embed-text-v1.5 em vez de v1
Considerada. v1.5 suporta Matryoshka (dimensões flexiveis, minimo 64). Rejeitada em
favor do v1 porque v1 tem dimensão fixa de 768 e é mais amplamente testado; v1.5
seria preferivel se precisassemos de dimensão menor que 768.

## Referências
- Groq modelos disponíveis: https://console.groq.com/docs/models (validado 2026-07-14)
- nomic-embed-text-v1 (HuggingFace): https://huggingface.co/nomic-ai/nomic-embed-text-v1
- OpenAI embedding models e Matryoshka: https://openai.com/index/new-embedding-models-and-api-updates/
- OpenAI pricing (text-embedding-3-small): $0,02/1M tokens
- ADR 001 - Arquitetura hexagonal (a criar na Fatia 0.2)
- ADR 003 - Estratégia de chunking (a criar na Fatia 2.3)
- ADR 004 - Índice PgVector HNSW vs IVFFlat (a criar na Fatia 3.1)
