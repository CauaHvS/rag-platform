package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.event.DocumentUploadedEvent;
import dev.ragplatform.domain.exception.DocumentNotFoundException;
import dev.ragplatform.domain.exception.UnsupportedDocumentTypeException;
import dev.ragplatform.domain.model.Document;
import dev.ragplatform.domain.port.out.ChunkRepository;
import dev.ragplatform.domain.port.out.DocumentRepository;
import dev.ragplatform.domain.port.out.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Set<String> TIPOS_PERMITIDOS = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown"
    );

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final FileStorage fileStorage;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(DocumentRepository documentRepository,
                           ChunkRepository chunkRepository,
                           FileStorage fileStorage,
                           ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.fileStorage = fileStorage;
        this.eventPublisher = eventPublisher;
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
        Document saved = documentRepository.save(document);

        // Publica evento APÓS o save; o listener dispara o job somente após o commit (AFTER_COMMIT).
        eventPublisher.publishEvent(new DocumentUploadedEvent(saved.id(), saved.ownerId()));
        return saved;
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

    /**
     * Remove o documento, seus chunks (+ vetores em cascata via FK) e o arquivo em disco.
     * Verifica a propriedade antes de apagar — lança {@link DocumentNotFoundException} se o
     * documento não existir ou não pertencer ao ownerId informado (sem vazar informação).
     */
    public void delete(UUID id, UUID ownerId) {
        Document doc = findByIdAndOwner(id, ownerId); // garante propriedade
        log.info("Excluindo documento id={} ownerId={}", id, ownerId);

        chunkRepository.deleteByDocumentId(id);       // chunks + vetores (FK cascade)
        fileStorage.delete(doc.storagePath());         // arquivo em disco
        documentRepository.delete(id);                // registro do documento
    }
}
