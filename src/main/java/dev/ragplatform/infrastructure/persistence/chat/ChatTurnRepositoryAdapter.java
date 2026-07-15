package dev.ragplatform.infrastructure.persistence.chat;

import dev.ragplatform.domain.model.ChatTurn;
import dev.ragplatform.domain.port.out.ChatTurnRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ChatTurnRepositoryAdapter implements ChatTurnRepository {

    private final ChatTurnJpaRepository jpaRepo;

    public ChatTurnRepositoryAdapter(ChatTurnJpaRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public ChatTurn save(ChatTurn turn) {
        var entity = new ChatTurnJpaEntity(turn.ownerId(), turn.question(), turn.answer(),
                turn.createdAt() != null ? turn.createdAt() : Instant.now());
        var saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public List<ChatTurn> findByOwner(UUID ownerId) {
        return jpaRepo.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(this::toDomain)
                .toList();
    }

    private ChatTurn toDomain(ChatTurnJpaEntity e) {
        return new ChatTurn(e.getId(), e.getOwnerId(), e.getQuestion(), e.getAnswer(), e.getCreatedAt());
    }
}
