package dev.ragplatform.infrastructure.persistence.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatTurnJpaRepository extends JpaRepository<ChatTurnJpaEntity, UUID> {
    List<ChatTurnJpaEntity> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
