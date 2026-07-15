package dev.ragplatform.infrastructure.ai.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Cache de embeddings de query no Redis.
 *
 * Chave: "emb:{sha256(text)}" — garante chaves curtas e uniformes para qualquer texto.
 * Valor: float[] serializado como Base64(bytes little-endian, 4 bytes por float).
 * TTL: 1 hora (embeddings de queries repetidas são estáveis ao longo do dia).
 *
 * Em produção multi-nó o Redis centraliza o cache; em testes usa-se o Redis do Testcontainers.
 */
@Service
public class EmbeddingQueryCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingQueryCache.class);
    private static final Duration TTL = Duration.ofHours(1);
    private static final String PREFIX = "emb:";

    private final StringRedisTemplate redis;

    public EmbeddingQueryCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<float[]> get(String text) {
        String key = key(text);
        String value = redis.opsForValue().get(key);
        if (value == null) return Optional.empty();
        try {
            float[] embedding = decode(value);
            log.debug("Cache HIT para embedding (key={})", key);
            return Optional.of(embedding);
        } catch (Exception e) {
            log.warn("Falha ao deserializar embedding do cache: {}", e.getMessage());
            redis.delete(key);
            return Optional.empty();
        }
    }

    public void put(String text, float[] embedding) {
        String key = key(text);
        redis.opsForValue().set(key, encode(embedding), TTL);
        log.debug("Embedding cacheado (key={}, dim={})", key, embedding.length);
    }

    // ── Serialização ─────────────────────────────────────────────────────────

    private static String encode(float[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : arr) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] arr = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < arr.length; i++) arr[i] = buf.getFloat();
        return arr;
    }

    private static String key(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes());
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível", e);
        }
    }
}
