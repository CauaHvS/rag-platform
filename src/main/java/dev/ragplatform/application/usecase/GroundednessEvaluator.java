package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.port.out.ChatProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Avalia se uma resposta gerada está fundamentada nos trechos recuperados (groundedness).
 *
 * Usa LLM-as-judge: envia contexto + pergunta + resposta para o ChatProvider configurado
 * e interpreta o veredicto (SIM / NÃO) do modelo.
 *
 * LIMITAÇÃO CONHECIDA: LLM-as-judge é ruidoso — o mesmo par (pergunta, resposta) pode
 * receber veredictos diferentes em execuções consecutivas. Use o resultado como indicador
 * de tendência (piorou / melhorou) em vez de nota absoluta. Ver ADR 009.
 *
 * Para rodar a avaliação real é necessário CHAT_PROVIDER=groq e GROQ_API_KEY definidos.
 * Em testes de CI o FakeChatProvider retorna uma resposta fixa e serve para validar
 * a lógica de parsing sem consumir cota.
 */
@Service
public class GroundednessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(GroundednessEvaluator.class);

    private final ChatProvider chatProvider;
    private final String judgeSystemPrompt;

    public GroundednessEvaluator(ChatProvider chatProvider) {
        this.chatProvider = chatProvider;
        this.judgeSystemPrompt = loadPrompt();
    }

    /**
     * Avalia se {@code answer} está fundamentada em {@code context} para a {@code question}.
     *
     * @return resultado com veredicto (grounded) e explicação do juiz
     */
    public GroundednessResult evaluate(String question, String answer, String context) {
        String userMessage = """
                CONTEXTO RECUPERADO:
                %s

                PERGUNTA DO USUÁRIO: %s

                RESPOSTA AVALIADA:
                %s

                Avalie se a resposta está fundamentada exclusivamente no contexto acima.
                """.formatted(context, question, answer);

        log.debug("Avaliando groundedness — question.len={} answer.len={}", question.length(), answer.length());
        String verdict = chatProvider.chat(judgeSystemPrompt, userMessage);
        return parseVerdict(question, answer, verdict);
    }

    /**
     * Avalia uma lista de casos e agrega os resultados em um relatório.
     *
     * @param cases pares (pergunta, resposta, contexto) a avaliar
     * @return relatório com taxa de groundedness e resultados individuais
     */
    public GroundednessReport evaluateBatch(List<EvaluationCase> cases) {
        List<GroundednessResult> results = cases.stream()
                .map(c -> evaluate(c.question(), c.answer(), c.context()))
                .toList();

        GroundednessReport report = GroundednessReport.of(results);
        log.info("Groundedness — rate={:.0f}% ({}/{} fundamentadas)",
                report.groundednessRate() * 100,
                results.stream().filter(GroundednessResult::grounded).count(),
                results.size());
        return report;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private GroundednessResult parseVerdict(String question, String answer, String response) {
        boolean grounded = response.contains("VEREDICTO: SIM");
        String explanation = extractExplanation(response);
        log.debug("Veredicto: {} | {}", grounded ? "FUNDAMENTADA" : "NÃO FUNDAMENTADA", explanation);
        return new GroundednessResult(question, answer, grounded, explanation);
    }

    private static String extractExplanation(String response) {
        int idx = response.indexOf("EXPLICAÇÃO:");
        if (idx == -1) return response.trim();
        return response.substring(idx + "EXPLICAÇÃO:".length()).trim();
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("prompts/groundedness-judge.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar prompts/groundedness-judge.txt", e);
        }
    }

    // ── DTOs de avaliação ────────────────────────────────────────────────────

    /** Entrada para avaliação: tripla (pergunta, resposta gerada, contexto recuperado). */
    public record EvaluationCase(String question, String answer, String context) {}

    /** Resultado individual da avaliação de um caso. */
    public record GroundednessResult(String question, String answer,
                                      boolean grounded, String explanation) {}

    /** Resultado agregado de uma avaliação em lote. */
    public record GroundednessReport(List<GroundednessResult> results, double groundednessRate) {

        public static GroundednessReport of(List<GroundednessResult> results) {
            double rate = results.isEmpty() ? 0.0
                    : (double) results.stream().filter(GroundednessResult::grounded).count()
                            / results.size();
            return new GroundednessReport(results, rate);
        }

        /** Imprime tabela legível dos resultados. */
        public String toTable() {
            var sb = new StringBuilder();
            sb.append(String.format("Groundedness: %.0f%% (%d/%d fundamentadas)%n",
                    groundednessRate * 100,
                    results.stream().filter(GroundednessResult::grounded).count(),
                    results.size()));
            sb.append("─".repeat(80)).append("\n");
            for (var r : results) {
                sb.append(String.format("[%s] %s%n", r.grounded() ? "SIM" : "NÃO",
                        r.question().length() > 60 ? r.question().substring(0, 60) + "…" : r.question()));
                sb.append(String.format("     → %s%n", r.explanation()));
            }
            return sb.toString();
        }
    }
}
