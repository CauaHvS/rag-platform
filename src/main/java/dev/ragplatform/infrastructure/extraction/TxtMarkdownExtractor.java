package dev.ragplatform.infrastructure.extraction;

import dev.ragplatform.domain.port.out.TextExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Extrai texto de arquivos text/plain e text/markdown.
 * Lê o conteúdo como UTF-8.
 */
@Component
public class TxtMarkdownExtractor implements TextExtractor {

    private static final Set<String> TIPOS = Set.of("text/plain", "text/markdown");

    @Override
    public boolean supports(String mimeType) {
        return TIPOS.contains(mimeType);
    }

    @Override
    public String extract(InputStream content, String originalName) throws IOException {
        return new String(content.readAllBytes(), StandardCharsets.UTF_8);
    }
}
