package dev.ragplatform.infrastructure.persistence.chunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface ChunkJpaRepository extends JpaRepository<ChunkJpaEntity, UUID> {

    @Modifying
    @Query("DELETE FROM ChunkJpaEntity c WHERE c.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);
}
