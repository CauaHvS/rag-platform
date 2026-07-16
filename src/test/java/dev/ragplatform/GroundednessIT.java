package dev.ragplatform;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ragplatform.application.usecase.GroundednessEvaluator;
import dev.ragplatform.application.usecase.GroundednessEvaluator.EvaluationCase;
import dev.ragplatform.application.usecase.GroundednessEvaluator.GroundednessReport;
import dev.ragplatform.application.usecase.GroundednessEvaluator.GroundednessResult;
import dev.ragplatform.infrastructure.ai.groq.GroqChatProvider;
import dev.ragplatform.infrastructure.observability.AiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Avaliação de groundedness (fundamentação) das respostas RAG via LLM-as-judge.
 *
 * EXECUÇÃO:
 *   Requer GROQ_API_KEY definida como variável de ambiente.
 *   Skippada automaticamente em CI (sem cota de IA).
 *
 *   ./mvnw verify -DGROQ_API_KEY=<sua-chave>
 *   ou: export GROQ_API_KEY=<sua-chave> && ./mvnw verify
 *
 * METODOLOGIA:
 *   - 5 casos de avaliação derivados do golden set (dataset.json)
 *   - 4 respostas fundamentadas (extraídas diretamente do contexto)
 *   - 1 resposta com alucinação explícita (fato não presente no contexto)
 *   - Threshold mínimo: groundedness rate >= 0.80 (4/5 fundamentadas)
 *
 * LIMITAÇÃO:
 *   LLM-as-judge é ruidoso — use o resultado como indicador de tendência,
 *   não como nota absoluta. Detalhe no ADR 009.
 */
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class GroundednessIT {

    private static final Logger log = LoggerFactory.getLogger(GroundednessIT.class);
    private static final double MIN_GROUNDEDNESS_RATE = 0.80;

    // ── Contextos do golden set ───────────────────────────────────────────────

    private static final String CTX_RNN =
            "Redes neurais artificiais são modelos computacionais inspirados no funcionamento " +
            "do cérebro humano. Compostas por neurônios artificiais organizados em camadas, " +
            "estas redes aprendem padrões a partir de dados de treinamento. O processo de " +
            "aprendizado ocorre através de ajustes nos pesos das conexões entre neurônios, " +
            "utilizando algoritmos como backpropagation. Redes profundas, com múltiplas camadas " +
            "ocultas, são capazes de aprender representações hierárquicas dos dados, identificando " +
            "características cada vez mais abstratas em cada nível da rede. Aplicações incluem " +
            "reconhecimento de imagem, processamento de linguagem natural e sistemas de recomendação.";

    private static final String CTX_PGVECTOR =
            "PgVector é uma extensão do PostgreSQL que adiciona suporte a vetores de alta dimensão " +
            "e operações de similaridade. Permite armazenar embeddings diretamente no banco de dados " +
            "relacional e executar buscas de vizinhos mais próximos usando índices HNSW ou IVFFlat. " +
            "A distância coseno e a distância euclidiana são as métricas mais utilizadas. " +
            "A integração com o ecossistema PostgreSQL simplifica arquiteturas RAG ao eliminar a " +
            "necessidade de um banco vetorial separado como Pinecone ou Weaviate. " +
            "Índices HNSW oferecem melhor recall e velocidade de consulta para conjuntos de dados grandes.";

    private static final String CTX_RAG =
            "Retrieval-Augmented Generation, ou RAG, é uma técnica que combina recuperação de " +
            "informação com geração de texto por modelos de linguagem. O pipeline RAG inicia com " +
            "a conversão da pergunta em um vetor de embedding, seguida de busca semântica nos " +
            "documentos indexados. Os trechos mais relevantes são inseridos no contexto do prompt " +
            "enviado ao modelo de linguagem. Esta abordagem reduz alucinações e permite que o modelo " +
            "responda com base em documentos privados e atualizados, sem necessidade de fine-tuning. " +
            "A qualidade da recuperação é fundamental para a qualidade da resposta gerada.";

    private GroundednessEvaluator evaluator;

    @BeforeEach
    void setup() {
        String apiKey = System.getenv("GROQ_API_KEY");
        var metrics = new AiMetrics(new SimpleMeterRegistry());
        var groq = new GroqChatProvider(
                "https://api.groq.com/openai/v1",
                apiKey,
                "llama-3.3-70b-versatile",
                new ObjectMapper(),
                metrics);
        evaluator = new GroundednessEvaluator(groq);
    }

    // ── Testes individuais ────────────────────────────────────────────────────

    @Test
    void resposta_fundamentada_e_identificada_como_grounded() {
        GroundednessResult result = evaluator.evaluate(
                "Como funciona o backpropagation em redes neurais?",
                "O backpropagation ajusta os pesos das conexões entre neurônios com base " +
                "nos dados de treinamento para que a rede aprenda padrões.",
                CTX_RNN);

        log.info("Veredicto: {} | {}", result.grounded() ? "SIM" : "NÃO", result.explanation());
        assertThat(result.grounded())
                .as("Resposta fundamentada no contexto deve ser identificada como grounded")
                .isTrue();
    }

    @Test
    void resposta_com_alucinacao_e_identificada_como_nao_grounded() {
        // Fato inventado: quem criou o PgVector e em que linguagem — não está no contexto
        GroundednessResult result = evaluator.evaluate(
                "Qual a vantagem do PgVector sobre bancos vetoriais separados?",
                "O PgVector foi criado em 2020 pela equipe do Crunchy Data usando Python, " +
                "o que facilita a integração com frameworks de machine learning.",
                CTX_PGVECTOR);

        log.info("Veredicto: {} | {}", result.grounded() ? "SIM" : "NÃO", result.explanation());
        assertThat(result.grounded())
                .as("Resposta com informações não presentes no contexto deve ser não-grounded")
                .isFalse();
    }

    // ── Avaliação em lote ─────────────────────────────────────────────────────

    @Test
    void avaliacao_em_lote_taxa_groundedness_atinge_minimo() {
        List<EvaluationCase> casos = List.of(
                new EvaluationCase(
                        "Como funciona o backpropagation?",
                        "O backpropagation ajusta os pesos das conexões entre neurônios " +
                        "usando dados de treinamento.",
                        CTX_RNN),
                new EvaluationCase(
                        "O que é PgVector e quais índices suporta?",
                        "PgVector é uma extensão do PostgreSQL para armazenar embeddings, " +
                        "com suporte a índices HNSW e IVFFlat para busca de vizinhos próximos.",
                        CTX_PGVECTOR),
                new EvaluationCase(
                        "Como RAG reduz alucinações?",
                        "RAG insere os trechos mais relevantes no contexto do prompt enviado " +
                        "ao modelo de linguagem, permitindo respostas baseadas em documentos reais.",
                        CTX_RAG),
                new EvaluationCase(
                        "Quais aplicações de redes neurais profundas?",
                        "Redes profundas com múltiplas camadas ocultas são usadas em " +
                        "reconhecimento de imagem e processamento de linguagem natural.",
                        CTX_RNN),
                // Caso com alucinação — o juiz deve detectar
                new EvaluationCase(
                        "Qual a vantagem do PgVector?",
                        "O PgVector suporta vetores de até 2048 dimensões e foi criado em 2020 " +
                        "pela equipe do Crunchy Data. Ele é escrito em Python.",
                        CTX_PGVECTOR)
        );

        GroundednessReport report = evaluator.evaluateBatch(casos);

        log.info("\n{}", report.toTable());

        // O último caso tem alucinação — deve ser detectado como NÃO fundamentado
        GroundednessResult ultimo = report.results().getLast();
        assertThat(ultimo.grounded())
                .as("Resposta com alucinação explícita deve ser detectada como não-grounded")
                .isFalse();

        // Taxa mínima: 4/5 = 0.80 (os 4 casos fundamentados passam, o alucinado falha)
        assertThat(report.groundednessRate())
                .as("Taxa de groundedness deve ser >= %.0f%%", MIN_GROUNDEDNESS_RATE * 100)
                .isGreaterThanOrEqualTo(MIN_GROUNDEDNESS_RATE);
    }
}
