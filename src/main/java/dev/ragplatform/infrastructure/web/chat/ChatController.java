package dev.ragplatform.infrastructure.web.chat;

import dev.ragplatform.application.usecase.ChatService;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de chat RAG.
 *
 * POST /api/chat — recebe pergunta, executa pipeline RAG e retorna resposta + fontes.
 * A chave de API do LLM nunca sai do backend.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request,
            @AuthenticationPrincipal UserPrincipal user) {

        var answer = chatService.chat(user.getId(), request.question(), request.kOrDefault());
        return ResponseEntity.ok(ChatResponse.from(answer));
    }
}
