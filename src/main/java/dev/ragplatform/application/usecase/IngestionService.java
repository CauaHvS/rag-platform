package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.exception.DocumentNotFoundException;
import dev.ragplatform.domain.model.Chunk;
import dev.ragplatform.domain.model.Document;
import dev.ragplatform.domain.model.DocumentStatus;
import dev.ragplatform.domain.port.out.ChunkRepository;
import dev.ragplatform.domain.port.out.DocumentRepository;
import dev.ragplatform.domain.port.out.FileStorage;
import dev.ragplatform.domain.port.out.TextChunker;
import dev.ragplatform.domain.port.out.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pipeline de ingestão de documentos.
 *
 * Fluxo (Fatia 2.1 — sem embeddings ainda):
 *   PENDING → EXTRACTING → CHUNKING → READY
 *                                   ↘ FAILED (qualquer erro)
 *
 * Idempotência: chunks existentes são removidos antes de recriar.
 * Retomabilidade completa (chunk-a-chunk) é trabalho futuro.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final FileStorage fileStorage;
    private final List<TextExtractor> extractors;
    private final TextChunker chunker;

    public IngestionService(DocumentRepository documentRepository,
                            ChunkRepository chunkRepository,
                            FileStorage fileStorage,
                            List<TextExtractor> extractors,
                            TextChunker chunker) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.fileStorage = fileStorage;
        this.extractors = extractors;
        this.chunker = chunker;
    }

    /**
     * Executa o pipeline de ingestão de forma assíncrona.
     * Deve ser chamado APÓS o commit da transação de upload (via @TransactionalEventListener).
     */
    @Async("ingestionExecutor")
    @Transactional
    public CompletableFuture<Void> processDocument(UUID documentId, UUID ownerId) {
        Document doc = documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // Ignora se já foi processado ou está em processamento por outro thread
        if (doc.status() != DocumentStatus.PENDING) {
            log.warn("Documento {} já está em estado {}; ignorando.", documentId, doc.status());
            return CompletableFuture.completedFuture(null);
        }

        try {
            // ── Extração ────────────────────────────────────────────────────
            doc = documentRepository.save(doc.withStatus(DocumentStatus.EXTRACTING));
            log.info("[{}] Extraindo texto ({}).", documentId, doc.mimeType());

            String text = extractText(doc);
            log.info("[{}] Texto extraído: {} chars.", documentId, text.length());

            // ── Chunking ─────────────────────────────────────────────────────
            doc = documentRepository.save(doc.withStatus(DocumentStatus.CHUNKING));

            List<TextChunker.ChunkContent> chunkContents = chunker.chunk(text);
            log.info("[{}] {} chunks gerados.", documentId, chunkContents.size());

            // Idempotência: remove chunks anteriores antes de recriar
            chunkRepository.deleteByDocumentId(documentId);

            List<Chunk> chunks = buildChunks(documentId, ownerId, chunkContents);
            chunkRepository.saveAll(chunks);

            // ── READY ─────────────────────────────────────────────────────────
            // Embedding será adicionado na Fatia 2.2; por ora vai direto para READY.
            documentRepository.save(doc.withStatus(DocumentStatus.READY));
            log.info("[{}] Ingestão concluída com {} chunks.", documentId, chunks.size());

        } catch (Exception e) {
            log.error("[{}] Falha na ingestão: {}", documentId, e.getMessage(), e);
            String erro = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500))
                    : e.getClass().getSimpleName();
            documentRepository.save(doc.withError(erro));
        }

        return CompletableFuture.completedFuture(null);
    }

    private String extractText(Document doc) throws IOException {
        TextExtractor extractor = extractors.stream()
                .filter(e -> e.supports(doc.mimeType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Nenhum extrator para o tipo: " + doc.mimeType()));

        try (InputStream input = fileStorage.load(doc.storagePath())) {
            return extractor.extract(input, doc.originalName());
        }
    }

    private List<Chunk> buildChunks(UUID documentId, UUID ownerId,
                                    List<TextChunker.ChunkContent> contents) {
        return java.util.stream.IntStream.range(0, contents.size())
                .mapToObj(i -> {
                    TextChunker.ChunkContent c = contents.get(i);
                    return Chunk.of(documentId, ownerId, i, c.content(), c.charStart(), c.charEnd());
                })
                .toList();
    }
}
