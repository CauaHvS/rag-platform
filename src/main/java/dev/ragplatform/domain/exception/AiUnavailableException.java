package dev.ragplatform.domain.exception;

/**
 * Lançada quando o provedor de IA (LLM ou embeddings) falha após todas as tentativas de retry.
 * Mapeada para HTTP 503 no GlobalExceptionHandler.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
