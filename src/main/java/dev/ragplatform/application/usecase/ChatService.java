package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.model.ChatAnswer;
import dev.ragplatform.domain.model.SimilarChunk;
import dev.ragplatform.domain.port.out.ChatProvider;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import dev.ragplatform.domain.port.out.VectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
    private final String promptTemplate;

    public ChatService(EmbeddingProvider embeddingProvider,
                       VectorRepository vectorRepository,
                       ChatProvider chatProvider) {
        this.embeddingProvider = embeddingProvider;
        this.vectorRepository = vectorRepository;
        this.chatProvider = chatProvider;
        this.promptTemplate = loadPromptTemplate();
    }

    public ChatAnswer chat(UUID ownerId, String question, int k) {
        log.info("Chat RAG — ownerId={} k={} question.length={}", ownerId, k, question.length());

        float[] queryEmbedding = embeddingProvider.embedQuery(question);
        List<SimilarChunk> sources = vectorRepository.findSimilar(ownerId, queryEmbedding, k);

        log.debug("Chunks recuperados: {}", sources.size());

        String systemPrompt = promptTemplate.replace("{context}", buildContext(sources));
        String answer = chatProvider.chat(systemPrompt, question);

        return new ChatAnswer(answer, sources);
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
