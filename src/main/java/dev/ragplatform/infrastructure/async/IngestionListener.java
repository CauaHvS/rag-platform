package dev.ragplatform.infrastructure.async;

import dev.ragplatform.application.usecase.IngestionService;
import dev.ragplatform.domain.event.DocumentUploadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escuta DocumentUploadedEvent e dispara o pipeline de ingestão de forma assíncrona.
 *
 * AFTER_COMMIT garante que o documento já esteja visível no banco antes de o job iniciar.
 * Sem isso, o job poderia tentar carregar o documento antes do commit da transação de upload.
 */
@Component
public class IngestionListener {

    private static final Logger log = LoggerFactory.getLogger(IngestionListener.class);

    private final IngestionService ingestionService;

    public IngestionListener(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        log.info("Documento {} recebido — agendando ingestão.", event.documentId());
        ingestionService.processDocument(event.documentId(), event.ownerId());
    }
}
