# ADR 004 - Índice PgVector: HNSW vs IVFFlat

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

O pgvector suporta dois tipos de índice para busca aproximada de vizinhos mais
próximos (ANN):

**HNSW (Hierarchical Navigable Small World)**
- Grafo hierárquico multicamada construído incrementalmente.
- Inserção: O(log n) — eficiente para inserções contínuas.
- Query: muito rápido, qualidade de recall alta por padrão.
- Parâmetros: `m` (grau máximo do grafo) e `ef_construction` (tamanho da fila na
  construção). Consulta usa `hnsw.ef_search` (padrão 40).
- Memória: maior que IVFFlat — o grafo precisa de RAM para ter boa performance.
- Não requer número de clusters definido antes de criar.

**IVFFlat (Inverted File with Flat compression)**
- Divide os vetores em listas (clusters) via k-means antes de indexar.
- Inserção: O(1) por vetor depois do treinamento, mas o índice precisa ser
  construído com dados já existentes (não online).
- Query: percorre `nprobe` listas; mais listas = maior recall, maior latência.
- Parâmetros: `lists` (número de clusters, √n é regra heurística) e `nprobe`.
- Memória: menor que HNSW.
- Requer que o índice seja construído depois de um volume mínimo de dados.

**Contexto do projeto:**
- Volume esperado: centenas a poucos milhares de chunks por usuário em portfólio.
- Padrão de escrita: contínuo (novos chunks adicionados com cada documento ingerido).
- Padrão de leitura: queries de busca em tempo real (latência importa).
- Operação: sem equipe de DBA; reconstrução de índice não é aceitável no fluxo normal.

## Decisão

Adotar **HNSW** com os parâmetros:

```sql
CREATE INDEX chunks_embedding_hnsw_idx
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

**Justificativa dos parâmetros:**
- `m = 16`: grau máximo do grafo. Valor padrão do pgvector. Boa relação
  qualidade/memória para volumes abaixo de 1M vetores.
- `ef_construction = 64`: tamanho da fila durante a construção. Maior = melhor
  qualidade do índice, mais lento para construir. 64 é o padrão; adequado para o
  volume esperado.
- `vector_cosine_ops`: operador de distância coseno, consistente com o operador `<=>`
  usado nas queries e com o tipo de embedding (vetores normalizados).

**Nota sobre dev vs prod:** com poucos dados (dezenas de chunks em dev), o PostgreSQL
pode optar por seq-scan em vez do índice HNSW (o planejador decide que seq-scan é
mais barato). Isso é correto e não é um bug. O índice passa a ser usado quando o
volume de dados justifica o overhead de navegar o grafo.

## Consequências

### Positivas
- Inserções incrementais sem reconstrução: cada novo chunk é adicionado ao grafo
  HNSW sem invalidar o índice inteiro. Adequado para o padrão de ingestão contínua.
- Recall alto com parâmetros padrão: HNSW com m=16, ef_search=40 tipicamente atinge
  recall@10 > 0.95 em benchmarks públicos (ann-benchmarks.com).
- Sem necessidade de dimensionar `lists` antes de ter dados: IVFFlat requer que se
  estime o volume total de vetores; HNSW cresce organicamente.
- Operacionalmente simples: sem passo de treinamento ou reconstrução periódica.

### Negativas
- Maior uso de memória RAM que IVFFlat. Para escala de milhões de vetores, o grafo
  HNSW pode consumir GBs de RAM para performance ótima.
- Construção do índice é mais lenta que IVFFlat para grandes volumes (mas acontece
  uma vez; inserções posteriores são O(log n)).
- `ef_search` padrão (40) pode ser insuficiente para recall muito alto em volumes
  grandes — ajuste necessário em produção com muitos documentos.

## Alternativas consideradas

### IVFFlat
Considerada. Menor memória, mas rejeitada porque:
(a) requer reconstrução do índice quando o volume de dados cresce significativamente
(`lists` calculado no momento da criação; com dados novos o índice fica subótimo);
(b) inserções em tabelas já indexadas com IVFFlat não atualizam o índice (necessário
`VACUUM ANALYZE` e potencialmente `REINDEX`);
(c) o passo de `DISCARD PLAN`/`SET ivfflat.probes` em cada conexão adiciona
complexidade operacional;
(d) documentação do pgvector recomenda HNSW para casos de uso com inserções
contínuas.

### Sem índice (sequential scan)
Adequado apenas para volumes muito pequenos (< ~1000 chunks). Rejeitada para produção
porque o seq-scan faz distância coseno para cada vetor — O(n) por query, impraticável
com muitos documentos.

### DiskANN
Não disponível no pgvector estável no momento desta decisão. Promissor para grandes
volumes com datasets que não cabem em RAM, mas requer plugin externo.

## Referências
- pgvector HNSW documentation: https://github.com/pgvector/pgvector#hnsw
- pgvector IVFFlat documentation: https://github.com/pgvector/pgvector#ivfflat
- ANN Benchmarks: https://ann-benchmarks.com/
- Migration V5: `src/main/resources/db/migration/V5__chunks_embedding.sql`
- ADR 002 - Provedores de IA (define vector(768) como dimensão)
