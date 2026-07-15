# ADR 005 - Busca Híbrida: Vetorial + BM25 com RRF

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

A busca vetorial pura (similaridade coseno entre embedding da pergunta e embeddings
dos chunks) tem limitações conhecidas:

1. **Palavras exatas**: termos técnicos, siglas, nomes próprios e identificadores
   (ex: "cláusula 7.2", "CNPJ 12.345.678/0001-90") muitas vezes não são capturados
   bem por embeddings — o modelo generaliza semanticamente mas perde o match exato.
2. **Embeddings ruins para queries curtas**: perguntas de uma ou duas palavras têm
   embeddings menos discriminativos que textos longos.
3. **Out-of-vocabulary**: termos raros ou novos que o modelo não viu no treinamento
   produzem embeddings de baixa qualidade.

A busca full-text (BM25 via PostgreSQL tsvector/tsquery) tem vantagens complementares:
- Excelente para match exato e variações morfológicas (stemming).
- Determinística e barata (sem chamada de API de embedding).
- Funciona bem para queries técnicas e identificadores.

Mas BM25 puro falha em:
- Sinônimos e paráfrases ("rescisão" vs "cancelamento do contrato").
- Perguntas em linguagem natural sem termos exatos no texto.

**A combinação é o estado da arte em RAG** (literatura: BEIR benchmark, Pinecone
sparse-dense hybrid, Weaviate BM25+vector).

## Decisão

Implementar **busca híbrida por padrão** combinando busca vetorial e BM25 via
**Reciprocal Rank Fusion (RRF)**.

### Fórmula RRF

```
score_rrf(chunk) = 1/(60 + rank_vetorial) + 1/(60 + rank_bm25)
```

Onde `rank` é a posição ordinal de cada chunk em seu ranking individual (1=melhor).
O constante 60 é o valor padrão da literatura (Cormack et al., 2009) que suaviza
a influência de chunks mal rankeados em um dos rankings.

### Implementação SQL

```sql
WITH vector_ranked AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rnk
    FROM chunks
    WHERE owner_id = ?::uuid AND embedding IS NOT NULL
    ORDER BY embedding <=> ?::vector LIMIT ?    -- over-fetch k×3
),
text_ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        ORDER BY ts_rank_cd(content_tsv, plainto_tsquery('portuguese', ?)) DESC
    ) AS rnk
    FROM chunks
    WHERE owner_id = ?::uuid
      AND content_tsv @@ plainto_tsquery('portuguese', ?)
    LIMIT ?                                      -- over-fetch k×3
),
rrf_scores AS (
    SELECT
        COALESCE(v.id, t.id) AS chunk_id,
        COALESCE(1.0/(60.0 + v.rnk), 0.0) + COALESCE(1.0/(60.0 + t.rnk), 0.0) AS rrf_score
    FROM vector_ranked v
    FULL OUTER JOIN text_ranked t ON v.id = t.id
)
SELECT c.id, c.document_id, c.content, c.char_start, c.char_end, r.rrf_score AS similarity
FROM rrf_scores r
JOIN chunks c ON c.id = r.chunk_id
ORDER BY r.rrf_score DESC LIMIT ?
```

**Decisões de implementação:**
- `over-fetch k×3` em cada sub-ranking: garante que chunks relevantes que aparecem
  apenas em um dos rankings sejam incluídos no merge.
- `FULL OUTER JOIN`: chunks que aparecem em apenas um ranking recebem score 0 do
  outro (equivalente a não ter aparecido — COALESCE para 0.0).
- `plainto_tsquery('portuguese', ?)`: converte texto livre em tsquery sem exigir
  operadores booleanos do usuário. Usa o dicionário `portuguese` para stemming.
- `content_tsv`: coluna `GENERATED ALWAYS AS STORED` com `to_tsvector('portuguese',
  content)`. Gerada automaticamente pelo Postgres, indexada com GIN.

### Modo de busca configurável

O endpoint `GET /api/search` aceita `?mode=hybrid` (padrão) ou `?mode=vector` para
comparação. O `ChatService` usa híbrido por padrão.

### Schema da coluna FTS

Migration V7:
```sql
ALTER TABLE chunks ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('portuguese', content)) STORED;
CREATE INDEX idx_chunks_content_tsv ON chunks USING gin(content_tsv);
```

## Avaliação quantitativa

Golden set: 3 documentos (redes neurais, pgvector, RAG), 5 perguntas por documento
= 15 perguntas. Testado com `EvaluationIT.java`.

| Modo | Recall@5 | MRR |
|---|---|---|
| Híbrido | 1.0 | 0.667 |
| Vetorial puro | <= 1.0 | <= 0.667 |

O teste `avaliacao_hibrida_recall_maior_ou_igual_ao_vetorial()` garante regressão:
mudanças que pioram a busca híbrida em relação à vetorial pura quebram o CI.

## Consequências

### Positivas
- Recall@5 = 1.0 no golden set: nenhuma pergunta deixou de ter o documento correto
  no top-5.
- Robusto a palavras exatas E paráfrases: combinação vetorial+BM25 cobre os pontos
  cegos de cada abordagem.
- Implementação em SQL puro (PostgreSQL + pgvector): sem biblioteca externa de rerank,
  sem serviço adicional, sem latência de rede extra.
- O isolamento multiusuário (`owner_id = ?`) é aplicado em **ambas** as sub-queries
  antes do merge — não há risco de vazar chunks de outros usuários no FULL OUTER JOIN.
- Modo configurável permite comparação A/B no mesmo endpoint.

### Negativas
- Query SQL mais complexa (3 CTEs + FULL OUTER JOIN) — mais difícil de manter e
  otimizar.
- Over-fetch k×3 significa mais vetores lidos do índice HNSW que no modo puro.
  Com volume alto pode aumentar latência.
- BM25 do PostgreSQL não suporta aprendizado de relevância (Learning to Rank). Para
  domínios muito específicos, um reranker neural (Cohere Rerank, FlashRank) daria
  melhor MRR, mas adicionaria latência e custo.
- `plainto_tsquery` não entende negação nem operadores booleanos do usuário. Para
  buscas avançadas seria necessário `websearch_to_tsquery`.

## Alternativas consideradas

### Busca vetorial pura
Simples e performática. Rejeitada como padrão por falhar em termos exatos e queries
técnicas. Mantida como opção via `?mode=vector` para comparação e benchmarking.

### Reranking com modelo neural (Cohere Rerank)
Considerado para melhorar o MRR. Rejeitado nesta fase porque: (a) adiciona chamada
de API externa com custo e latência; (b) MRR=0.667 é aceitável para o escopo;
(c) aumenta complexidade operacional. Candidato para uma Fatia 6 de refinamento.

### Elasticsearch / OpenSearch
Considerado para BM25 de alta performance. Rejeitado: (a) adiciona um serviço pesado
à stack; (b) duplica os dados (já temos no Postgres); (c) o BM25 do pgvector via
tsvector é suficiente para o volume esperado.

### Sparse vectors (SPLADE, BM42)
Considerado como alternativa ao tsvector clássico. SPLADE gera vetores esparsos que
capturam importância de termos de forma mais sofisticada que TF-IDF puro. Rejeitado:
não disponível como extensão nativa do PostgreSQL; requer serviço separado.

## Referências
- Cormack, Clarke, Buettcher — "Reciprocal Rank Fusion outperforms Condorcet and
  individual Rank Learning Methods" (SIGIR 2009)
- BEIR Benchmark: https://github.com/beir-cellar/beir
- pgvector full-text search: https://github.com/pgvector/pgvector#full-text-search
- ADR 003 - Estratégia de chunking (parâmetros do golden set)
- ADR 004 - Índice PgVector HNSW
- `VectorJdbcRepository.java` — implementação do SQL de busca híbrida
- `EvaluationIT.java` — teste de Recall@5 e MRR
