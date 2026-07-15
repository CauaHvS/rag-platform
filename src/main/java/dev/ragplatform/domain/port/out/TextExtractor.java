package dev.ragplatform.domain.port.out;

import java.io.IOException;
import java.io.InputStream;

/**
 * Porta de saída: extração de texto de um arquivo.
 * Cada implementação suporta um ou mais tipos MIME.
 */
public interface TextExtractor {
    boolean supports(String mimeType);
    String extract(InputStream content, String originalName) throws IOException;
}
