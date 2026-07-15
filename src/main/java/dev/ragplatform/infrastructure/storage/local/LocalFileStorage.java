package dev.ragplatform.infrastructure.storage.local;

import dev.ragplatform.domain.exception.StorageException;
import dev.ragplatform.domain.port.out.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

/**
 * Adaptador de armazenamento no sistema de arquivos local.
 * Estrutura: {storageRoot}/{documentId}/{nome-sanitizado}
 * Em produção, substituir por S3Adapter sem tocar no domínio.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path storageRoot;

    public LocalFileStorage(@Value("${app.storage.local-path:./uploads}") String path) {
        this.storageRoot = Path.of(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new StorageException("Não foi possível criar o diretório de armazenamento: " + storageRoot, e);
        }
        log.info("Armazenamento local inicializado em: {}", storageRoot);
    }

    @Override
    public String store(UUID documentId, String originalName, InputStream content, String mimeType) {
        String safeName = sanitize(originalName != null ? originalName : "arquivo");
        Path dir = storageRoot.resolve(documentId.toString());
        Path target = dir.resolve(safeName);
        try {
            Files.createDirectories(dir);
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Arquivo armazenado: {}", target);
        } catch (IOException e) {
            throw new StorageException("Falha ao armazenar arquivo: " + safeName, e);
        }
        return documentId + "/" + safeName;
    }

    @Override
    public void delete(String storagePath) {
        Path file = storageRoot.resolve(storagePath);
        try {
            Files.deleteIfExists(file);
            // Remove o diretório pai se estiver vazio
            Path parent = file.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                try (var entries = Files.list(parent)) {
                    if (entries.findFirst().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
        } catch (IOException e) {
            // Delete é melhor esforço; loga mas não propaga
            log.warn("Não foi possível remover arquivo de storage: {}", storagePath, e);
        }
    }

    /** Remove caracteres que não sejam alfanuméricos, ponto, traço ou underscore. */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
