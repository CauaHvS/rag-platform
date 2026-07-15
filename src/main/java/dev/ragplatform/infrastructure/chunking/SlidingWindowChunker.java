package dev.ragplatform.infrastructure.chunking;

import dev.ragplatform.domain.port.out.TextChunker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Divisor de texto em chunks por janela deslizante com sobreposição.
 *
 * Parâmetros (configuráveis via construtor para testes):
 *   chunkSize = 1500 chars ≈ 512 tokens para a maioria dos modelos
 *   overlap   = 200 chars  — contexto compartilhado entre chunks adjacentes
 */
@Component
public class SlidingWindowChunker implements TextChunker {

    private final int chunkSize;
    private final int overlap;

    public SlidingWindowChunker() {
        this(1500, 200);
    }

    SlidingWindowChunker(int chunkSize, int overlap) {
        if (overlap >= chunkSize) throw new IllegalArgumentException("overlap deve ser menor que chunkSize");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<ChunkContent> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<ChunkContent> result = new ArrayList<>();
        int step = chunkSize - overlap;
        int len = text.length();
        int start = 0;

        while (start < len) {
            int end = Math.min(start + chunkSize, len);
            result.add(new ChunkContent(text.substring(start, end), start, end));
            if (end == len) break;
            start += step;
        }
        return result;
    }
}
