package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.exception.DocumentNotFoundException;
import dev.ragplatform.domain.exception.UnsupportedDocumentTypeException;
import dev.ragplatform.domain.model.Document;
import dev.ragplatform.domain.port.out.DocumentRepository;
import dev.ragplatform.domain.port.out.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class DocumentService {

    private static final Set<String> TIPOS_PERMITIDOS = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown"
    );

    private final DocumentRepository documentRepository;
    private final FileStorage fileStorage;

    public DocumentService(DocumentRepository documentRepository, FileStorage fileStorage) {
        this.documentRepository = documentRepository;
        this.fileStorage = fileStorage;
    }

    /**
     * Recebe um arquivo, valida o tipo, persiste no storage e cria o registro
     * com status PENDING. O processamento assíncrono (OCR, chunking, embeddings)
     * será iniciado pelo job de background (Fatia 2.x).
     */
    public Document upload(UUID ownerId, String originalName, String mimeType,
                           long fileSize, InputStream content) {
        String tipoEfetivo = mimeType != null ? mimeType : "application/octet-stream";
        if (!TIPOS_PERMITIDOS.contains(tipoEfetivo)) {
            throw new UnsupportedDocumentTypeException(tipoEfetivo);
        }

        UUID docId = UUID.randomUUID();
        String storagePath = fileStorage.store(docId, originalName, content, tipoEfetivo);

        Document document = Document.newUpload(docId, ownerId, originalName,
                tipoEfetivo, fileSize, storagePath);
        return documentRepository.save(document);
    }

    @Transactional(readOnly = true)
    public Document findByIdAndOwner(UUID id, UUID ownerId) {
        return documentRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Document> listByOwner(UUID ownerId) {
        return documentRepository.findAllByOwnerId(ownerId);
    }
}
