package dev.ragplatform.domain.port.out;

import java.io.InputStream;
import java.util.UUID;

/**
 * Porta de saída: armazenamento de arquivos.
 * Implementação default: sistema de arquivos local.
 * Em produção pode ser substituída por S3 sem alterar o domínio.
 */
public interface FileStorage {
    /**
     * Armazena o arquivo e retorna o caminho relativo dentro do storage.
     * Lança {@link dev.ragplatform.domain.exception.StorageException} em caso de falha de I/O.
     */
    String store(UUID documentId, String originalName, InputStream content, String mimeType);

    /** Remove o arquivo. Idempotente: não lança exceção se já não existir. */
    void delete(String storagePath);
}
