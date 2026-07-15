package dev.ragplatform.domain.port.out;

import java.util.List;

/** Porta de saída: divisão de texto em chunks para indexação. */
public interface TextChunker {

    List<ChunkContent> chunk(String text);

    /** Conteúdo e posição de um chunk dentro do texto original. */
    record ChunkContent(String content, int charStart, int charEnd) {}
}
