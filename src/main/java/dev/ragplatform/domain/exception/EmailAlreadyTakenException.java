package dev.ragplatform.domain.exception;

public class EmailAlreadyTakenException extends RuntimeException {

    public EmailAlreadyTakenException(String email) {
        super("E-mail já em uso: " + email);
    }
}
