package dev.ragplatform.domain.port.out;

import dev.ragplatform.domain.model.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: persistência de documentos. */
public interface DocumentRepository {
    Document save(Document document);
    Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId);
    List<Document> findAllByOwnerId(UUID ownerId);
    /** Remove o documento. Não verifica propriedade — a verificação ocorre no caso de uso. */
    void delete(UUID id);
}
