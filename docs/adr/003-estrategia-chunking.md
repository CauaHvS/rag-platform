# ADR 003 - Estratégia de Chunking

**Status:** Aceito
**Data:** 2026-07-15

## Contexto

O pipeline RAG precisa dividir documentos em trechos (chunks) antes de gerar os
embeddings. O tamanho e a estratégia de divisão afetam diretamente:

1. **Qualidade de recuperação**: chunks muito grandes diluem o sinal semântico do
   embedding; chunks muito pequenos perdem contexto necessário para a resposta.
2. **Custo de embedding**: chunks menores = mais chunks = mais chamadas ao provedor
   de embeddings = maior custo.
3. **Tamanho do contexto enviado ao LLM**: cada chunk recuperado ocupa tokens no
   prompt. Chunks grandes com k=5 podem exceder o contexto do modelo.
4. **Coerência de resposta**: o LLM precisa de contexto suficiente para citar a
   fonte corretamente. Um chunk que começa no meio de uma frase é inútil.

**Restrições do modelo de embedding escolhido (nomic-embed-text-v1):**
- Janela de contexto: 8192 tokens.
- ~4 chars por token em média para português.
- 8192 tokens × 4 chars ≈ 32 768 chars máximo por chunk — muito acima do útil.

**Restrições do LLM (Groq / Llama 3.3 70B):**
- Contexto: 128k tokens.
- Com k=5 chunks de 1500 chars ≈ 7500 chars ≈ 1875 tokens só de contexto.
- Margem suficiente para system prompt + pergunta + resposta.

## Decisão

Adotar **janela deslizante (sliding window) com sobreposição**:

- `chunkSize = 1500 chars` (≈ 375–500 tokens, zona de melhor relação qualidade/custo)
- `overlap = 200 chars` (≈ 50 tokens de contexto compartilhado entre chunks adjacentes)
- `step = chunkSize - overlap = 1300 chars` por chunk

**Razão do overlap**: sentenças que atravessam a fronteira entre dois chunks consecutivos
aparecem parcialmente em ambos. Sem overlap, uma pergunta cujo trecho relevante cai
exatamente na fronteira pode não ser recuperada. 200 chars é suficiente para cobrir
2–4 sentenças de transição sem duplicar excessivamente o conteúdo.

**Implementação**: `SlidingWindowChunker` em `infrastructure/chunking/`, parâmetros
configuráveis via construtor (facilita testes com valores menores).

**Tracking de posição**: cada chunk armazena `char_start` e `char_end` no texto
original. Permite reconstruir a citação exata no documento e implementar
highlighting futuro.

## Consequências

### Positivas
- Implementação simples e determinística: dado o mesmo texto, os mesmos chunks são
  gerados sempre (sem randomicidade, idempotência garantida).
- 1500 chars está dentro da janela "sweet spot" documentada em benchmarks RAG
  (Anthropic, LlamaIndex): recall melhora até ~512 tokens; depois estabiliza ou cai.
- Overlap de 200 chars reduz falsos negativos em fronteiras sem custo significativo
  (≈15% de tokens duplicados por chunk adjacente).
- Parâmetros testados no golden set: Recall@5=1.0, MRR=0.667 com 15 perguntas.

### Negativas
- Janela deslizante ignora estrutura semântica do documento (parágrafos, seções,
  listas). Um chunk pode começar no meio de uma tabela ou lista numerada.
- Não preserva metadata de estrutura (título da seção, número de página).
- Para documentos muito pequenos (<1500 chars), gera um único chunk com o documento
  inteiro — bom para recuperação, mas pode diluir embeddings.
- A divisão por caracteres não é determinística em termos de tokens: um texto com
  muitos emojis ou caracteres CJK pode ter mais tokens que o esperado por char.

## Alternativas consideradas

### Chunking por parágrafo ou sentença
Considerada. Preserva coerência semântica melhor que janela fixa. Rejeitada porque:
(a) requer parser de linguagem natural (SpaCy, OpenNLP) — dependência pesada;
(b) tamanho variável torna o batching de embeddings menos eficiente;
(c) documentos mal formatados (PDF sem quebras de parágrafo corretas) geram um único
parágrafo enorme.

### Chunking hierárquico (HippoRAG, proposição-level)
Considerada. Divide em proposições atômicas (uma ideia por chunk) para máxima
precisão. Rejeitada: requer LLM para segmentação (custo alto de ingestão) e latência
muito maior no pipeline. Adequado para RAG de alta precisão em domínios críticos,
não para o escopo deste portfólio.

### chunkSize = 512 chars
Considerado. Menor = mais granular = melhor precisão de recuperação em teoria.
Rejeitado: (a) mais chunks por documento = mais embeddings = maior custo; (b) chunks
pequenos perdem contexto — a resposta do LLM fica pobre; (c) testes preliminares com
o golden set mostraram recall equivalente ao de 1500 chars com overhead 3× maior.

### chunkSize = 3000 chars, sem overlap
Considerado. Maior = menos chunks = menor custo. Rejeitado: (a) embeddings de textos
longos tendem a ser menos discriminativos (média de muitos conceitos); (b) envia mais
tokens desnecessários ao LLM com cada chunk recuperado.

## Referências
- Benchmarks de tamanho de chunk: LlamaIndex blog, "Chunk Size Matters" (2023)
- nomic-embed-text-v1 — contexto 8192 tokens: https://huggingface.co/nomic-ai/nomic-embed-text-v1
- ADR 002 - Provedores de IA (define dimensão e provedor de embedding)
- ADR 005 - Busca híbrida (avaliação com golden set)
- `EvaluationIT.java` — teste que valida Recall@5 e MRR contra o chunking atual
