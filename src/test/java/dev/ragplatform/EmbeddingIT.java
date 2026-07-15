package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.document.DocumentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EmbeddingIT {

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
    @Autowired JdbcTemplate jdbcTemplate;

    private String token;

    @BeforeEach
    void setup() {
        token = registrarELogar("Carlos", "carlos@example.com", "senha1234C");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void aposIngestao_chunksTemEmbeddingNaoNulo() {
        // Texto longo o suficiente para gerar ao menos 1 chunk
        String texto = "Engenharia de software é a aplicação sistemática de princípios de engenharia "
                .repeat(30);

        UUID docId = upload("engenharia.txt", "text/plain", texto.getBytes()).id();

        // Aguarda READY
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(getStatus(docId)).isEqualTo("READY"));

        // FakeEmbeddingProvider gera vetores de zeros — mas eles existem (não nulos)
        Long chunksComEmbedding = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE document_id = ?::uuid AND embedding IS NOT NULL",
                Long.class, docId.toString());

        assertThat(chunksComEmbedding).isGreaterThan(0);
    }

    @Test
    void documentoCurto_umChunk_comEmbedding() {
        String texto = "Cláusula única: este contrato rege a prestação de serviços.";

        UUID docId = upload("clausula.txt", "text/plain", texto.getBytes()).id();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(getStatus(docId)).isEqualTo("READY"));

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE document_id = ?::uuid", Long.class, docId.toString());
        Long comEmbedding = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE document_id = ?::uuid AND embedding IS NOT NULL",
                Long.class, docId.toString());

        assertThat(total).isEqualTo(1);
        assertThat(comEmbedding).isEqualTo(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private DocumentResponse upload(String filename, String contentType, byte[] content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);

        var res = new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        };
        var part = new HttpEntity<>(res, singleHeader(HttpHeaders.CONTENT_TYPE, contentType));
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", part);

        var resp = restTemplate.exchange("/api/documents", HttpMethod.POST,
                new HttpEntity<>(body, h), DocumentResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return resp.getBody();
    }

    private String getStatus(UUID docId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange("/api/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(h), DocumentResponse.class).getBody().status();
    }

    private HttpHeaders singleHeader(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.set(name, value);
        return h;
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
