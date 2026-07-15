package dev.ragplatform.infrastructure.web;

import dev.ragplatform.domain.exception.DocumentNotFoundException;
import dev.ragplatform.domain.exception.EmailAlreadyTakenException;
import dev.ragplatform.domain.exception.StorageException;
import dev.ragplatform.domain.exception.UnsupportedDocumentTypeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 400 — falha de validação nos DTOs (@Valid) */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Dados inválidos");
        problem.setType(URI.create("https://ragplatform.dev/errors/validation"));

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map((FieldError fe) -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 413 — arquivo excede o limite configurado em spring.servlet.multipart.max-file-size.
     * Spring 6.2+ inclui MaxUploadSizeExceededException no handleException da superclasse;
     * sobrescrevemos o método protegido para customizar a resposta.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Arquivo muito grande. O limite é 20 MB.");
        problem.setTitle("Arquivo muito grande");
        problem.setType(URI.create("https://ragplatform.dev/errors/file-too-large"));
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /** 415 — tipo de arquivo não suportado */
    @ExceptionHandler(UnsupportedDocumentTypeException.class)
    ProblemDetail handleUnsupportedType(UnsupportedDocumentTypeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Tipo de arquivo não suportado");
        problem.setType(URI.create("https://ragplatform.dev/errors/unsupported-type"));
        return problem;
    }

    /** 404 — documento não encontrado ou não pertence ao usuário (não revelar qual) */
    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleDocumentNotFound(DocumentNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Documento não encontrado");
        problem.setType(URI.create("https://ragplatform.dev/errors/not-found"));
        return problem;
    }

    /** 500 — falha de I/O no storage */
    @ExceptionHandler(StorageException.class)
    ProblemDetail handleStorage(StorageException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Falha ao processar o arquivo. Tente novamente.");
        problem.setTitle("Erro de armazenamento");
        problem.setType(URI.create("https://ragplatform.dev/errors/storage"));
        return problem;
    }

    /** 409 — e-mail já cadastrado */
    @ExceptionHandler(EmailAlreadyTakenException.class)
    ProblemDetail handleEmailTaken(EmailAlreadyTakenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("E-mail já cadastrado");
        problem.setType(URI.create("https://ragplatform.dev/errors/email-taken"));
        return problem;
    }

    /** 401 — credenciais inválidas */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos.");
        problem.setTitle("Credenciais inválidas");
        problem.setType(URI.create("https://ragplatform.dev/errors/unauthorized"));
        return problem;
    }

    /**
     * 403 — autenticado mas sem permissão.
     * Sem este handler explícito, AuthorizationDeniedException vira 500.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Acesso negado.");
        problem.setTitle("Sem permissão");
        problem.setType(URI.create("https://ragplatform.dev/errors/forbidden"));
        return problem;
    }
}
