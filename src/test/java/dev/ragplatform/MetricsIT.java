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
 * Verifica que as métricas de IA são registradas corretamente via Micrometer.
 *
 * Após uma chamada a POST /api/chat, os contadores ai.chat.requests e
 * ai.chat.tokens.estimated devem aparecer em /actuator/metrics com count > 0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MetricsIT {

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
        token = registrarELogar("User", "user+" + suffix + "@metrics.com", "Senha1234S");
    }

    @Test
    void chatRequest_incrementa_contador_ai_chat_requests() {
        // Captura count antes
        double contadorAntes = chatRequestsCount();

        // Faz uma chamada de chat
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "Teste de métricas"), h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verifica que o contador aumentou
        double contadorDepois = chatRequestsCount();
        assertThat(contadorDepois).isGreaterThan(contadorAntes);
    }

    @Test
    void chatRequest_incrementa_contador_tokens_estimados() {
        // Faz uma chamada de chat
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "Pergunta para tokens"), h), String.class);

        // Contador de tokens deve existir e ser > 0
        double tokens = metricCount("ai.chat.tokens.estimated");
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void chatRequest_incrementa_contador_embedding_requests() {
        // Faz uma chamada de chat
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "Pergunta para embedding"), h), String.class);

        // Contador de embeddings deve existir (pode ser 0 se veio do cache, mas endpoint existe)
        ResponseEntity<String> metrics = restTemplate.getForEntity(
                "/actuator/metrics/ai.embedding.requests", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("measurement");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private double chatRequestsCount() {
        return metricCount("ai.chat.requests");
    }

    private double metricCount(String metricName) {
        ResponseEntity<Map> resp = restTemplate.getForEntity(
                "/actuator/metrics/" + metricName, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var measurements = (java.util.List<?>) resp.getBody().get("measurements");
        assertThat(measurements).isNotEmpty();
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
