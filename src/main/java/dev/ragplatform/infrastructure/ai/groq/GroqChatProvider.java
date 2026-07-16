package dev.ragplatform.infrastructure.ai.groq;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ragplatform.domain.exception.AiUnavailableException;
import dev.ragplatform.domain.port.out.ChatProvider;
import dev.ragplatform.infrastructure.observability.AiMetrics;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Adapter de chat via API do Groq (compatível com OpenAI).
 *
 * Ativo quando CHAT_PROVIDER=groq. Usa llama-3.3-70b-versatile por padrão.
 * A chave de API é injetada por env var GROQ_API_KEY — nunca em código nem no repo.
 *
 * Resiliência (configurada em application.yml / resilience4j):
 *   - @Retry(name="groq-chat")      — 3 tentativas com backoff exponencial
 *   - @CircuitBreaker(name="groq-chat") — abre após 50% de falhas em 10 chamadas;
 *                                         fica aberto 30 s antes de ir para half-open
 *
 * chat()   — POST /chat/completions bloqueante via RestClient (timeout 10 s).
 * stream() — POST /chat/completions com stream:true via Java HttpClient;
 *            BodyHandlers.ofLines() mantém a conexão aberta enquanto o Stream
 *            não for fechado. O caller usa try-with-resources.
 */
@Component
@ConditionalOnProperty(name = "app.chat.provider", havingValue = "groq")
public class GroqChatProvider implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqChatProvider.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT    = Duration.ofSeconds(10);

    private static final String PROVIDER = "groq";

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AiMetrics aiMetrics;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    public GroqChatProvider(
            @Value("${app.chat.base-url}") String baseUrl,
            @Value("${app.chat.api-key}") String apiKey,
            @Value("${app.chat.model}") String model,
            ObjectMapper objectMapper,
            AiMetrics aiMetrics) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.aiMetrics = aiMetrics;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // ── Bloqueante ────────────────────────────────────────────────────────────

    /**
     * Ordem dos aspectos: Retry (outer, order=3) → CircuitBreaker (inner, order=2) → método.
     * Se CB estiver aberto lança CallNotPermittedException; o Retry está configurado para
     * ignorar essa exceção (não tenta novamente), e o fallback é chamado imediatamente.
     */
    @Override
    @CircuitBreaker(name = "groq-chat")
    @Retry(name = "groq-chat", fallbackMethod = "chatFallback")
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
        if (response.usage() != null) {
            aiMetrics.recordChatTokens(PROVIDER, model, currentUserId(),
                    response.usage().prompt_tokens(), response.usage().completion_tokens());
        }
        return response.choices().getFirst().message().content();
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    /** CB protege a abertura da conexão; falha lazy do stream é responsabilidade do caller. */
    @Override
    @CircuitBreaker(name = "groq-chat", fallbackMethod = "streamFallback")
    public Stream<String> stream(String systemPrompt, String userMessage) {
        String body;
        try {
            body = objectMapper.writeValueAsString(new StreamRequest(model,
                    List.of(new Message("system", systemPrompt), new Message("user", userMessage)),
                    0.1, true, new StreamOptions(true)));
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
                            .timeout(READ_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Groq stream retornou HTTP " + response.statusCode());
            }

            AtomicReference<StreamUsage> usageRef = new AtomicReference<>();
            String userId = currentUserId();
            return response.body()
                    .filter(line -> line.startsWith("data: ") && !line.equals("data: [DONE]"))
                    .peek(line -> captureStreamUsage(line.substring(6), usageRef))
                    .map(line -> extractToken(line.substring(6)))
                    .filter(token -> !token.isEmpty())
                    .onClose(() -> {
                        StreamUsage u = usageRef.get();
                        if (u != null) {
                            aiMetrics.recordChatTokens(PROVIDER, model, userId,
                                    u.prompt_tokens(), u.completion_tokens());
                        }
                    });

        } catch (IOException e) {
            throw new UncheckedIOException("Falha na chamada streaming ao Groq", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Streaming Groq interrompido", e);
        }
    }

    // ── Fallbacks ─────────────────────────────────────────────────────────────

    /** Chamado após esgotar retries OU quando o circuit breaker está aberto. */
    public String chatFallback(String systemPrompt, String userMessage, Throwable ex) {
        log.error("Groq chat indisponível: {}", ex.getMessage());
        throw new AiUnavailableException("LLM temporariamente indisponível. Tente novamente em instantes.", ex);
    }

    public Stream<String> streamFallback(String systemPrompt, String userMessage, Throwable ex) {
        log.error("Groq stream indisponível: {}", ex.getMessage());
        throw new AiUnavailableException("LLM temporariamente indisponível para streaming.", ex);
    }

    // ── Extração de token SSE ─────────────────────────────────────────────────

    private String extractToken(String jsonChunk) {
        try {
            var node = objectMapper.readTree(jsonChunk);
            var choices = node.path("choices");
            if (choices.isEmpty()) return "";
            var content = choices.get(0).path("delta").path("content");
            return content.isMissingNode() || content.isNull() ? "" : content.asText();
        } catch (Exception e) {
            log.debug("Chunk SSE não parseável: {}", jsonChunk);
            return "";
        }
    }

    /**
     * Detecta o chunk de usage (choices vazio + campo "usage" presente) e armazena na ref.
     * Enviado pelo Groq quando stream_options.include_usage=true.
     */
    private void captureStreamUsage(String jsonChunk, AtomicReference<StreamUsage> ref) {
        try {
            var node = objectMapper.readTree(jsonChunk);
            var usageNode = node.path("usage");
            if (!usageNode.isMissingNode() && node.path("choices").isEmpty()) {
                ref.set(new StreamUsage(
                        usageNode.path("prompt_tokens").asInt(),
                        usageNode.path("completion_tokens").asInt()));
            }
        } catch (Exception ignored) {}
    }

    /** Retorna o UUID do usuário autenticado no contexto atual, ou "anonymous". */
    private static String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return "anonymous";
        if (auth.getPrincipal() instanceof UserPrincipal up) return up.getId().toString();
        return auth.getName();
    }

    // ── DTOs internos ────────────────────────────────────────────────────────

    record SyncRequest(String model, List<Message> messages, double temperature) {}
    record StreamRequest(String model, List<Message> messages, double temperature,
                         boolean stream, StreamOptions stream_options) {}
    record StreamOptions(boolean include_usage) {}
    record Message(String role, String content) {}
    record ChatResponse(List<Choice> choices, Usage usage) {
        record Choice(Message message) {}
    }
    record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {}
    record StreamUsage(int prompt_tokens, int completion_tokens) {}
}
