package dev.ragplatform.infrastructure.web;

import dev.ragplatform.domain.exception.EmailAlreadyTakenException;
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

    /** 409 — e-mail já cadastrado */
    @ExceptionHandler(EmailAlreadyTakenException.class)
    ProblemDetail handleEmailTaken(EmailAlreadyTakenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("E-mail já cadastrado");
        problem.setType(URI.create("https://ragplatform.dev/errors/email-taken"));
        return problem;
    }

    /** 401 — credenciais inválidas (BadCredentialsException e similares) */
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
     * ARMADILHA: sem este handler explícito, AuthorizationDeniedException vira 500
     * quando capturada por um handler genérico (@ExceptionHandler(Exception.class)).
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
