package dev.ragplatform.infrastructure.web.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ragplatform.application.usecase.ChatService;
import dev.ragplatform.application.usecase.ChatStreamContext;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.ragplatform.domain.model.ChatTurn;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de chat RAG.
 *
 * POST /api/chat         — resposta síncrona completa.
 * POST /api/chat/stream  — Server-Sent Events:
 *     event:sources  data:[{chunkId,documentId,content,similarity}]  (JSON)
 *     event:token    data:<fragmento>                                 (texto)
 *     event:done     data:
 *
 * A chave de API do LLM nunca sai do backend.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    // ── Histórico ────────────────────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<List<ChatTurnResponse>> history(@AuthenticationPrincipal UserPrincipal user) {
        List<ChatTurnResponse> turns = chatService.getHistory(user.getId()).stream()
                .map(ChatTurnResponse::from)
                .toList();
        return ResponseEntity.ok(turns);
    }

    // ── Síncrono ─────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserPrincipal user) {

        var answer = chatService.chat(user.getId(), request.question(), request.kOrDefault());
        return ResponseEntity.ok(ChatResponse.from(answer));
    }

    // ── Streaming SSE ─────────────────────────────────────────────────────────

    /**
     * As fontes são serializadas para JSON String antes de enviar ao SseEmitter —
     * evita busca de HttpMessageConverter fora do contexto MVC no virtual thread.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserPrincipal user) {

        UUID ownerId = user.getId();
        String question = request.question();
        int k = request.kOrDefault();

        SseEmitter emitter = new SseEmitter(120_000L);

        Thread.ofVirtual().start(() -> {
            try {
                // 1. Embed + busca vetorial + monta prompt
                ChatStreamContext ctx = chatService.prepareStream(ownerId, question, k);

                // 2. Fontes serializadas aqui (ObjectMapper thread-safe; String passada ao emitter)
                List<ChatResponse.SourceResponse> sources = ctx.sources().stream()
                        .map(s -> new ChatResponse.SourceResponse(
                                s.chunkId(), s.documentId(), s.content(), s.similarity()))
                        .toList();
                String sourcesJson = toJson(sources);
                emitter.send(SseEmitter.event().name("sources").data(sourcesJson));

                // 3. Tokens do LLM em streaming
                var answerBuilder = new StringBuilder();
                try (var tokens = chatService.streamTokens(ctx.systemPrompt(), question)) {
                    tokens.forEach(token -> {
                        answerBuilder.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }

                // 4. Persiste o turno completo
                chatService.saveTurn(ownerId, question, answerBuilder.toString());

                // 5. Sinal de conclusão
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

            } catch (Exception e) {
                log.error("Erro no streaming de chat: {}", e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar SSE payload", e);
        }
    }
}
