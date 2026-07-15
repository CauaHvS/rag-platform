package dev.ragplatform.infrastructure.persistence.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentJpaRepository extends JpaRepository<DocumentJpaEntity, UUID> {
    Optional<DocumentJpaEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    List<DocumentJpaEntity> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
}
