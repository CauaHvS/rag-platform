package dev.ragplatform.infrastructure.ai.groq;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ragplatform.domain.exception.AiUnavailableException;
import dev.ragplatform.domain.port.out.ChatProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;

/**
 * Adapter de chat via API do Groq (compatível com OpenAI).
 *
 * Ativo quando CHAT_PROVIDER=groq. Usa llama-3.3-70b-versatile por padrão.
 * A chave de API é injetada por env var GROQ_API_KEY — nunca em código nem no repo.
 *
 * chat()   — POST /chat/completions bloqueante via RestClient.
 * stream() — POST /chat/completions com stream:true via Java HttpClient;
 *            BodyHandlers.ofLines() mantém a conexão aberta enquanto o Stream
 *            não for fechado. O caller usa try-with-resources.
 */
@Component
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "groq")
public class GroqChatProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqChatProvider.class);

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    public GroqChatProvider(
            @Value("${app.chat.base-url}") String baseUrl,
            @Value("${app.chat.api-key}") String apiKey,
            @Value("${app.chat.model}") String model,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ── Bloqueante ────────────────────────────────────────────────────────────

    @Override
    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2, maxDelay = 4000)
    )
    public String chat(String systemPrompt, String userMessage) {
        var request = new SyncRequest(model,
                List.of(new Message("system", systemPrompt), new Message("user", userMessage)),
                0.1);

        log.debug("Groq chat — modelo={} pergunta.len={}", model, userMessage.length());

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

    // ── Streaming ─────────────────────────────────────────────────────────────

    @Override
    public Stream<String> stream(String systemPrompt, String userMessage) {
        String body;
        try {
            body = objectMapper.writeValueAsString(new StreamRequest(model,
                    List.of(new Message("system", systemPrompt), new Message("user", userMessage)),
                    0.1, true));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao serializar request Groq", e);
        }

        log.debug("Groq stream — modelo={} pergunta.len={}", model, userMessage.length());

        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/chat/completions"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Groq stream retornou HTTP " + response.statusCode());
            }

            // Cada linha SSE: "data: {...}" ou "data: [DONE]"
            // Extrai choices[0].delta.content de cada chunk
            return response.body()
                    .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                    .map(line -> extractToken(line.substring(6)))
                    .filter(token -> !token.isEmpty());

        } catch (IOException e) {
            throw new UncheckedIOException("Falha na chamada streaming ao Groq", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Streaming Groq interrompido", e);
        }
    }

    @Recover
    public String chatFallback(RuntimeException ex, String systemPrompt, String userMessage) {
        log.error("Groq chat indisponível após retries: {}", ex.getMessage());
        throw new AiUnavailableException("LLM indisponível após 3 tentativas.", ex);
    }

    private String extractToken(String jsonChunk) {
        try {
            var node = objectMapper.readTree(jsonChunk);
            var content = node.path("choices").get(0).path("delta").path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText();
        } catch (Exception e) {
            log.debug("Chunk SSE não parseável: {}", jsonChunk);
            return "";
        }
    }

    // ── DTOs internos ────────────────────────────────────────────────────────

    record SyncRequest(String model, List<Message> messages, double temperature) {}

    record StreamRequest(String model, List<Message> messages, double temperature, boolean stream) {}

    record Message(String role, String content) {}

    record ChatResponse(List<Choice> choices) {
        record Choice(Message message) {}
    }
}
