package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.model.ChatAnswer;
import dev.ragplatform.domain.model.ChatTurn;
import dev.ragplatform.domain.model.SimilarChunk;
import dev.ragplatform.domain.port.out.ChatProvider;
import dev.ragplatform.domain.port.out.ChatTurnRepository;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import dev.ragplatform.domain.port.out.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Caso de uso de chat RAG.
 *
 * Fluxo: embed query → busca vetorial → monta system prompt com contexto → LLM → resposta + fontes.
 * O system prompt é carregado de classpath:prompts/system.txt (versionado como código).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final EmbeddingProvider embeddingProvider;
    private final VectorRepository vectorRepository;
    private final ChatProvider chatProvider;
    private final ChatTurnRepository chatTurnRepository;
    private final String promptTemplate;

    public ChatService(EmbeddingProvider embeddingProvider,
                       VectorRepository vectorRepository,
                       ChatProvider chatProvider,
                       ChatTurnRepository chatTurnRepository) {
        this.embeddingProvider = embeddingProvider;
        this.vectorRepository = vectorRepository;
        this.chatProvider = chatProvider;
        this.chatTurnRepository = chatTurnRepository;
        this.promptTemplate = loadPromptTemplate();
    }

    public ChatAnswer chat(UUID ownerId, String question, int k) {
        log.info("Chat RAG — ownerId={} k={} question.length={}", ownerId, k, question.length());

        float[] queryEmbedding = embeddingProvider.embedQuery(question);
        List<SimilarChunk> sources = vectorRepository.findSimilar(ownerId, queryEmbedding, k);

        log.debug("Chunks recuperados: {}", sources.size());

        String systemPrompt = promptTemplate.replace("{context}", buildContext(sources));
        String answer = chatProvider.chat(systemPrompt, question);

        chatTurnRepository.save(new ChatTurn(null, ownerId, question, answer, Instant.now()));

        return new ChatAnswer(answer, sources);
    }

    /**
     * Passo 1 do pipeline streaming: embed query + busca vetorial + monta prompt.
     * Não faz chamada ao LLM — retorna contexto pronto para streamTokens().
     */
    public ChatStreamContext prepareStream(UUID ownerId, String question, int k) {
        log.info("Chat stream prepare — ownerId={} k={}", ownerId, k);
        float[] queryEmbedding = embeddingProvider.embedQuery(question);
        List<SimilarChunk> sources = vectorRepository.findSimilar(ownerId, queryEmbedding, k);
        String systemPrompt = promptTemplate.replace("{context}", buildContext(sources));
        return new ChatStreamContext(sources, systemPrompt);
    }

    /**
     * Passo 2 do pipeline streaming: chama o LLM em modo stream.
     * O caller é responsável por fechar o Stream retornado (try-with-resources).
     */
    public Stream<String> streamTokens(String systemPrompt, String question) {
        return chatProvider.stream(systemPrompt, question);
    }

    /** Persiste um turno de chat originado pelo streaming (chamado pelo controller após acumular a resposta). */
    public void saveTurn(UUID ownerId, String question, String answer) {
        chatTurnRepository.save(new ChatTurn(null, ownerId, question, answer, Instant.now()));
    }

    /** Retorna o histórico de turnos do usuário, do mais recente ao mais antigo. */
    public List<ChatTurn> getHistory(UUID ownerId) {
        return chatTurnRepository.findByOwner(ownerId);
    }

    private String buildContext(List<SimilarChunk> sources) {
        if (sources.isEmpty()) {
            return "Nenhum trecho relevante encontrado nos documentos do usuário.";
        }
        var sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            sb.append("[Trecho ").append(i + 1).append("]\n");
            sb.append(sources.get(i).content()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String loadPromptTemplate() {
        try {
            return new ClassPathResource("prompts/system.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar prompts/system.txt", e);
        }
    }
}
