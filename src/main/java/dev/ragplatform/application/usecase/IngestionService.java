package dev.ragplatform.application.usecase;

import dev.ragplatform.domain.exception.DocumentNotFoundException;
import dev.ragplatform.domain.model.Chunk;
import dev.ragplatform.domain.model.Document;
import dev.ragplatform.domain.model.DocumentStatus;
import dev.ragplatform.domain.port.out.ChunkRepository;
import dev.ragplatform.domain.port.out.DocumentRepository;
import dev.ragplatform.domain.port.out.EmbeddingProvider;
import dev.ragplatform.domain.port.out.FileStorage;
import dev.ragplatform.domain.port.out.TextChunker;
import dev.ragplatform.domain.port.out.TextExtractor;
import dev.ragplatform.domain.port.out.VectorRepository;
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
 * Fluxo (Fatia 2.2):
 *   PENDING → EXTRACTING → CHUNKING → EMBEDDING → READY
 *                                               ↘ FAILED (qualquer erro)
 *
 * Idempotência: chunks existentes são removidos antes de recriar.
 * Embeddings em lote (BATCH_SIZE) para respeitar limites de taxa do provedor.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BATCH_SIZE = 32;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorRepository vectorRepository;
    private final FileStorage fileStorage;
    private final List<TextExtractor> extractors;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddingProvider;

    public IngestionService(DocumentRepository documentRepository,
                            ChunkRepository chunkRepository,
                            VectorRepository vectorRepository,
                            FileStorage fileStorage,
                            List<TextExtractor> extractors,
                            TextChunker chunker,
                            EmbeddingProvider embeddingProvider) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorRepository = vectorRepository;
        this.fileStorage = fileStorage;
        this.extractors = extractors;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
    }

    @Async("ingestionExecutor")
    @Transactional
    public CompletableFuture<Void> processDocument(UUID documentId, UUID ownerId) {
        Document doc = documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (doc.status() != DocumentStatus.PENDING) {
            log.warn("Documento {} já está em estado {}; ignorando.", documentId, doc.status());
            return CompletableFuture.completedFuture(null);
        }

        try {
            // ── Extração ────────────────────────────────────────────────────
            doc = documentRepository.save(doc.withStatus(DocumentStatus.EXTRACTING));
            log.info("[{}] Extraindo texto ({}).", documentId, doc.mimeType());
            String text = extractText(doc);
            log.info("[{}] {} chars extraídos.", documentId, text.length());

            // ── Chunking ─────────────────────────────────────────────────────
            doc = documentRepository.save(doc.withStatus(DocumentStatus.CHUNKING));
            List<TextChunker.ChunkContent> chunkContents = chunker.chunk(text);
            chunkRepository.deleteByDocumentId(documentId);
            List<Chunk> savedChunks = chunkRepository.saveAll(
                    buildChunks(documentId, ownerId, chunkContents));
            log.info("[{}] {} chunks salvos.", documentId, savedChunks.size());

            // ── Embedding ────────────────────────────────────────────────────
            doc = documentRepository.save(doc.withStatus(DocumentStatus.EMBEDDING));
            embedChunks(savedChunks);
            log.info("[{}] Embeddings gerados para {} chunks.", documentId, savedChunks.size());

            // ── READY ─────────────────────────────────────────────────────────
            documentRepository.save(doc.withStatus(DocumentStatus.READY));
            log.info("[{}] Ingestão concluída.", documentId);

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
        try (InputStream in = fileStorage.load(doc.storagePath())) {
            return extractor.extract(in, doc.originalName());
        }
    }

    private void embedChunks(List<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            List<String> contents = batch.stream().map(Chunk::content).toList();
            List<float[]> embeddings = embeddingProvider.embedDocuments(contents);
            for (int j = 0; j < batch.size(); j++) {
                vectorRepository.saveEmbedding(batch.get(j).id(), embeddings.get(j));
            }
        }
    }

    private List<Chunk> buildChunks(UUID documentId, UUID ownerId,
                                    List<TextChunker.ChunkContent> contents) {
        return java.util.stream.IntStream.range(0, contents.size())
                .mapToObj(i -> {
                    var c = contents.get(i);
                    return Chunk.of(documentId, ownerId, i, c.content(), c.charStart(), c.charEnd());
                })
                .toList();
    }
}
