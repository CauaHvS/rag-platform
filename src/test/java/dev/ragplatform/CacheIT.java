package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica o comportamento do cache Redis de embeddings de query.
 *
 * Premissas testadas:
 *   - Uma pergunta nova → cache MISS → provedor de embeddings invocado
 *   - A mesma pergunta repetida → cache HIT → provedor NÃO invocado
 *
 * Evidência observável: o contador ai.embedding.requests (incrementado apenas em MISS)
 * não deve aumentar na segunda chamada idêntica, enquanto ai.embedding.cache.hits deve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CacheIT {

    @TempDir
    static Path storageDir;

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.storage.local-path", () -> storageDir.toString());
    }

    @Autowired TestRestTemplate restTemplate;

    private String token;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        token = registrarELogar("User", "user+" + suffix + "@cache.com", "Senha1234C");
    }

    @Test
    void segunda_chamada_identica_usa_cache_e_nao_chama_provedor() {
        String pergunta = "O que é embedding de query para cache? " + UUID.randomUUID();

        // 1ª chamada: MISS — provedor invocado, embedding armazenado no cache
        chat(pergunta);
        double missesApos1a = metricCount("ai.embedding.cache.misses");
        double hitsApos1a   = metricCount("ai.embedding.cache.hits");

        assertThat(missesApos1a).isGreaterThanOrEqualTo(1.0);

        // 2ª chamada com a mesma pergunta: HIT — provedor NÃO deve ser invocado
        chat(pergunta);
        double missesApos2a = metricCount("ai.embedding.cache.misses");
        double hitsApos2a   = metricCount("ai.embedding.cache.hits");

        // Misses não aumentam (provedor não foi chamado de novo)
        assertThat(missesApos2a).isEqualTo(missesApos1a);

        // Hits aumentam (cache serviu a segunda query)
        assertThat(hitsApos2a).isGreaterThan(hitsApos1a);
    }

    @Test
    void perguntas_diferentes_geram_misses_distintos() {
        String perguntaA = "Pergunta alfa " + UUID.randomUUID();
        String perguntaB = "Pergunta beta " + UUID.randomUUID();

        double missesBefore = metricCount("ai.embedding.cache.misses");

        chat(perguntaA);
        chat(perguntaB);

        double missesAfter = metricCount("ai.embedding.cache.misses");
        // Cada pergunta nova deve gerar exatamente 1 miss
        assertThat(missesAfter - missesBefore).isEqualTo(2.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void chat(String question) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", question), h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private double metricCount(String metricName) {
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                "/actuator/metrics/" + metricName, Map.class);
        if (resp.getStatusCode() == HttpStatus.NOT_FOUND) return 0.0;
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var measurements = (java.util.List<?>) resp.getBody().get("measurements");
        var first = (Map<?, ?>) measurements.get(0);
        return ((Number) first.get("value")).doubleValue();
    }

    private String registrarELogar(String name, String email, String password) {
        restTemplate.postForEntity("/auth/register",
                new HttpEntity<>(Map.of("name", name, "email", email, "password", password),
                        jsonHeaders()), AuthResponse.class);
        return restTemplate.postForEntity("/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()),
                AuthResponse.class).getBody().token();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
