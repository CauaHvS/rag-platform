package dev.ragplatform.infrastructure.web.chat;

import dev.ragplatform.domain.model.ChatTurn;

import java.time.Instant;
import java.util.UUID;

public record ChatTurnResponse(UUID id, String question, String answer, Instant createdAt) {

    public static ChatTurnResponse from(ChatTurn turn) {
        return new ChatTurnResponse(turn.id(), turn.question(), turn.answer(), turn.createdAt());
    }
}
