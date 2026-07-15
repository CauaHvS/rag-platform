package dev.ragplatform.domain.exception;

public class UnsupportedDocumentTypeException extends RuntimeException {
    public UnsupportedDocumentTypeException(String mimeType) {
        super("Tipo de arquivo não suportado: " + mimeType + ". Envie PDF ou texto simples.");
    }
}
