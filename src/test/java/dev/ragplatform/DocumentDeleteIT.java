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

/**
 * Testes de integração para DELETE /api/documents/{id}.
 *
 * Verifica:
 *   - 204 ao excluir documento próprio
 *   - Chunks removidos do banco em cascata
 *   - 404 ao tentar acessar documento excluído
 *   - ISOLAMENTO: usuário A não consegue excluir documento de B (404, sem vazar informação)
 *   - Idempotência: segundo DELETE retorna 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DocumentDeleteIT {

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

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tokenA = registrarELogar("Alice", "alice+" + suffix + "@delete.com", "Senha1234A");
        tokenB = registrarELogar("Bob",   "bob+"   + suffix + "@delete.com", "Senha1234B");
    }

    @Test
    void delete_proprio_documento_retorna_204() {
        UUID docId = upload(tokenA, "doc.txt", "Conteúdo de teste".getBytes()).id();

        ResponseEntity<Void> resp = delete(tokenA, docId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void apos_delete_documento_nao_e_encontrado() {
        UUID docId = upload(tokenA, "doc.txt", "Conteúdo de teste".getBytes()).id();
        delete(tokenA, docId);

        ResponseEntity<String> resp = get(tokenA, docId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void apos_delete_chunks_sao_removidos_do_banco() {
        // Texto longo o suficiente para gerar chunks após ingestão
        String texto = "Engenharia de software aplica princípios sistemáticos. ".repeat(40);
        UUID docId = upload(tokenA, "chunks.txt", texto.getBytes()).id();

        // Aguarda ingestão concluir para garantir que há chunks
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() ->
                        assertThat(getStatus(tokenA, docId)).isEqualTo("READY"));

        long chunksAntes = countChunks(docId);
        assertThat(chunksAntes).isGreaterThan(0);

        delete(tokenA, docId);

        long chunksDepois = countChunks(docId);
        assertThat(chunksDepois).isZero();
    }

    @Test
    void isolamento_usuario_A_nao_apaga_documento_de_B() {
        UUID docDeB = upload(tokenB, "doc-b.txt", "Documento de Bob".getBytes()).id();

        // A tenta apagar documento de B — deve receber 404 (sem vazar que o doc existe)
        ResponseEntity<Void> resp = delete(tokenA, docDeB);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Documento de B continua existindo
        assertThat(get(tokenB, docDeB).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void segundo_delete_retorna_404() {
        UUID docId = upload(tokenA, "doc.txt", "Conteúdo".getBytes()).id();
        delete(tokenA, docId);

        ResponseEntity<Void> resp = delete(tokenA, docId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DocumentResponse upload(String token, String filename, byte[] content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);

        var res = new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        };
        var part = new HttpEntity<>(res, singleHeader(HttpHeaders.CONTENT_TYPE, "text/plain"));
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", part);

        var resp = restTemplate.exchange("/api/documents", HttpMethod.POST,
                new HttpEntity<>(body, h), DocumentResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return resp.getBody();
    }

    private ResponseEntity<Void> delete(String token, UUID docId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange("/api/documents/" + docId, HttpMethod.DELETE,
                new HttpEntity<>(h), Void.class);
    }

    private ResponseEntity<String> get(String token, UUID docId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange("/api/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(h), String.class);
    }

    private String getStatus(String token, UUID docId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange("/api/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(h), DocumentResponse.class).getBody().status();
    }

    private long countChunks(UUID docId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunks WHERE document_id = ?::uuid",
                Long.class, docId.toString());
        return count != null ? count : 0L;
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

    private HttpHeaders singleHeader(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.set(name, value);
        return h;
    }
}
