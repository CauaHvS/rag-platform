package dev.ragplatform.infrastructure.web.document;

import dev.ragplatform.application.usecase.DocumentService;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** POST /api/documents — recebe o arquivo e retorna 202 com status PENDING. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentResponse upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) throws IOException {

        var document = documentService.upload(
                principal.getId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file.getInputStream()
        );
        return DocumentResponse.from(document);
    }

    /** GET /api/documents — lista documentos do usuário autenticado. */
    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return documentService.listByOwner(principal.getId())
                .stream().map(DocumentResponse::from).toList();
    }

    /** GET /api/documents/{id} — consulta status; 404 se não pertencer ao usuário. */
    @GetMapping("/{id}")
    public DocumentResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return DocumentResponse.from(documentService.findByIdAndOwner(id, principal.getId()));
    }

    /**
     * DELETE /api/documents/{id} — exclui documento, chunks e arquivo.
     * Retorna 204 No Content. 404 se o documento não existir ou não pertencer ao usuário.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        documentService.delete(id, principal.getId());
    }
}
