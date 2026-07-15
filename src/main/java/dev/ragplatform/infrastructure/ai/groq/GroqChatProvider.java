package dev.ragplatform.infrastructure.ai.groq;

import dev.ragplatform.domain.port.out.ChatProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Adapter de chat via API do Groq (compatível com OpenAI).
 *
 * Ativo quando CHAT_PROVIDER=groq. Usa llama-3.3-70b-versatile por padrão.
 * A chave de API é injetada por env var GROQ_API_KEY — nunca em código nem no repo.
 */
@Component
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "groq")
public class GroqChatProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqChatProvider.class);

    private final RestClient restClient;
    private final String model;

    public GroqChatProvider(
            @Value("${app.chat.base-url}") String baseUrl,
            @Value("${app.chat.api-key}") String apiKey,
            @Value("${app.chat.model}") String model) {
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        var request = new ChatRequest(model,
                List.of(
                        new Message("system", systemPrompt),
                        new Message("user", userMessage)
                ),
                0.1);

        log.debug("Chamando Groq — modelo={} userMessage.length={}", model, userMessage.length());

        ChatResponse response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(ChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("Groq retornou resposta vazia");
        }

        return response.choices().getFirst().message().content();
    }

    // ── DTOs internos (OpenAI-compatible) ───────────────────────────────────

    record ChatRequest(String model, List<Message> messages, double temperature) {}

    record Message(String role, String content) {}

    record ChatResponse(List<Choice> choices) {
        record Choice(Message message) {}
    }
}
