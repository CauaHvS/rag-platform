package dev.ragplatform.infrastructure.web.search;

import dev.ragplatform.application.usecase.SearchService;
import dev.ragplatform.infrastructure.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * GET /api/search?q=texto&k=5
     * Retorna os k chunks mais relevantes dos documentos do usuário autenticado.
     * Isolamento garantido: o filtro owner_id está no SQL da busca vetorial.
     */
    @GetMapping
    public List<SearchResponse> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int k) {
        return searchService.search(principal.getId(), q, Math.min(k, 20))
                .stream().map(SearchResponse::from).toList();
    }
}
