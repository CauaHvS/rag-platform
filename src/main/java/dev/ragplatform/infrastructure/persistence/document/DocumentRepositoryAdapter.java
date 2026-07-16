package dev.ragplatform.infrastructure.persistence.document;

import dev.ragplatform.domain.model.Document;
import dev.ragplatform.domain.port.out.DocumentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DocumentRepositoryAdapter implements DocumentRepository {

    private final DocumentJpaRepository jpaRepo;

    public DocumentRepositoryAdapter(DocumentJpaRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public Document save(Document document) {
        return toDomain(jpaRepo.save(toEntity(document)));
    }

    @Override
    public Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return jpaRepo.findByIdAndOwnerId(id, ownerId).map(this::toDomain);
    }

    @Override
    public List<Document> findAllByOwnerId(UUID ownerId) {
        return jpaRepo.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void delete(UUID id) {
        jpaRepo.deleteById(id);
    }

    private DocumentJpaEntity toEntity(Document d) {
        var e = new DocumentJpaEntity();
        e.setId(d.id());
        e.setOwnerId(d.ownerId());
        e.setOriginalName(d.originalName());
        e.setMimeType(d.mimeType());
        e.setFileSize(d.fileSize());
        e.setStoragePath(d.storagePath());
        e.setStatus(d.status());
        e.setErrorMessage(d.errorMessage());
        e.setCreatedAt(d.createdAt());
        e.setUpdatedAt(d.updatedAt());
        return e;
    }

    private Document toDomain(DocumentJpaEntity e) {
        return new Document(e.getId(), e.getOwnerId(), e.getOriginalName(),
                e.getMimeType(), e.getFileSize(), e.getStoragePath(),
                e.getStatus(), e.getErrorMessage(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
