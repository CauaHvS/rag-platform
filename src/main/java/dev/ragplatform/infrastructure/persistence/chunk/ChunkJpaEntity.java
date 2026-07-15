package dev.ragplatform.infrastructure.persistence.chunk;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chunks")
public class ChunkJpaEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "char_start", nullable = false)
    private int charStart;

    @Column(name = "char_end", nullable = false)
    private int charEnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getCharStart() { return charStart; }
    public void setCharStart(int charStart) { this.charStart = charStart; }

    public int getCharEnd() { return charEnd; }
    public void setCharEnd(int charEnd) { this.charEnd = charEnd; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
