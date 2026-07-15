package dev.ragplatform.domain.port.out;

import dev.ragplatform.domain.model.ChatTurn;

import java.util.List;
import java.util.UUID;

public interface ChatTurnRepository {
    ChatTurn save(ChatTurn turn);
    List<ChatTurn> findByOwner(UUID ownerId);
}
