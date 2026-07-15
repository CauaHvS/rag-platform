package dev.ragplatform.infrastructure.persistence.chunk;

import dev.ragplatform.domain.model.Chunk;
import dev.ragplatform.domain.port.out.ChunkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class ChunkRepositoryAdapter implements ChunkRepository {

    private final ChunkJpaRepository jpaRepo;

    public ChunkRepositoryAdapter(ChunkJpaRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public List<Chunk> saveAll(List<Chunk> chunks) {
        List<ChunkJpaEntity> entities = chunks.stream().map(this::toEntity).toList();
        return jpaRepo.saveAll(entities).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void deleteByDocumentId(UUID documentId) {
        jpaRepo.deleteByDocumentId(documentId);
    }

    @Override
    public long countByDocumentId(UUID documentId) {
        return jpaRepo.countByDocumentId(documentId);
    }

    private ChunkJpaEntity toEntity(Chunk c) {
        var e = new ChunkJpaEntity();
        e.setId(c.id());
        e.setDocumentId(c.documentId());
        e.setOwnerId(c.ownerId());
        e.setChunkIndex(c.chunkIndex());
        e.setContent(c.content());
        e.setCharStart(c.charStart());
        e.setCharEnd(c.charEnd());
        e.setCreatedAt(c.createdAt());
        return e;
    }

    private Chunk toDomain(ChunkJpaEntity e) {
        return new Chunk(e.getId(), e.getDocumentId(), e.getOwnerId(),
                e.getChunkIndex(), e.getContent(), e.getCharStart(), e.getCharEnd(), e.getCreatedAt());
    }
}
