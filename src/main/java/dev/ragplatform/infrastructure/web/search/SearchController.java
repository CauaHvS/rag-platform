package dev.ragplatform.infrastructure.web.search;

import dev.ragplatform.application.usecase.SearchService;
import dev.ragplatform.domain.model.SearchMode;
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
     * GET /api/search?q=texto&k=5&mode=hybrid
     *
     * mode=hybrid (padrão) — RRF vetorial + BM25 full-text.
     * mode=vector           — somente similaridade coseno (para comparação).
     *
     * Isolamento garantido: owner_id filtrado no SQL de ambos os modos.
     */
    @GetMapping
    public List<SearchResponse> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int k,
            @RequestParam(defaultValue = "hybrid") String mode) {
        SearchMode searchMode = "vector".equalsIgnoreCase(mode) ? SearchMode.VECTOR : SearchMode.HYBRID;
        return searchService.search(principal.getId(), q, Math.min(k, 20), searchMode)
                .stream().map(SearchResponse::from).toList();
    }
}
