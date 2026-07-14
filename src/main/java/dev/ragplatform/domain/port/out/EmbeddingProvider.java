package dev.ragplatform.domain.port.out;

import java.util.List;

/**
 * Porta de saída para geração de embeddings.
 *
 * Implementações disponíveis:
 *   - OllamaEmbeddingProvider  (dev — local, sem cota)
 *   - OpenAiEmbeddingProvider  (prod — text-embedding-3-small, dimensions=768)
 *   - FakeEmbeddingProvider    (testes — zeros, sem rede)
 *
 * Dimensão fixa: 768. Deve corresponder à coluna vector(768) no PgVector.
 * Trocar de modelo de embedding exige reindexar todos os chunks.
 */
public interface EmbeddingProvider {

    /**
     * Gera embedding para um trecho de documento.
     * Internamente aplica o prefixo de instrução correto (ex: "search_document:").
     */
    float[] embedDocument(String text);

    /**
     * Gera embedding para uma pergunta de busca.
     * Internamente aplica o prefixo de instrução correto (ex: "search_query:").
     */
    float[] embedQuery(String text);

    /**
     * Gera embeddings em lote. Use sempre que possível para evitar N chamadas ao provedor.
     * Os textos são tratados como documentos (prefixo search_document).
     */
    List<float[]> embedDocuments(List<String> texts);

    /**
     * Dimensão dos vetores gerados. Deve retornar 768 em todas as implementações.
     */
    int dimension();
}
