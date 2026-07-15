package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.document.DocumentResponse;
import dev.ragplatform.infrastructure.web.search.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
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
class SearchIT {

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

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setup() {
        // Sufixo único por teste para evitar acúmulo de documentos entre execuções
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tokenA = registrarELogar("Ana", "ana+" + suffix + "@search.com", "senha1234A");
        tokenB = registrarELogar("Bruno", "bruno+" + suffix + "@search.com", "senha1234B");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void buscaRetornaChunksDoProprioUsuario() {
        String texto = "Sistemas de recuperação de informação utilizam índices invertidos.".repeat(10);
        UUID docId = upload(tokenA, "ir.txt", "text/plain", texto.getBytes()).id();

        aguardarReady(docId, tokenA);

        SearchResponse[] results = buscar(tokenA, "recuperação de informação", 5);
        assertThat(results).isNotEmpty();
        // Todos os resultados devem pertencer ao único documento de A
        for (SearchResponse r : results) {
            assertThat(r.documentId()).isEqualTo(docId);
        }
    }

    @Test
    void isolamento_usuarioBNaoVeChunksDeA() {
        // A sobe documento e aguarda ingestão
        String texto = "Dados confidenciais da empresa XYZ.".repeat(20);
        UUID docId = upload(tokenA, "confidencial.txt", "text/plain", texto.getBytes()).id();
        aguardarReady(docId, tokenA);

        // ISOLAMENTO: B busca — não deve ver nada (não tem documentos)
        SearchResponse[] resultsB = buscar(tokenB, "dados confidenciais", 5);
        assertThat(resultsB).isEmpty();
    }

    @Test
    void buscaSemDocumentos_retornaListaVazia() {
        SearchResponse[] results = buscar(tokenB, "qualquer query", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void buscaSemToken_retorna401() {
        HttpHeaders h = new HttpHeaders();
        var resp = restTemplate.exchange("/api/search?q=teste", HttpMethod.GET,
                new HttpEntity<>(h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void limitePorK_respeitado() {
        // Texto longo para gerar muitos chunks
        String texto = "C".repeat(10000);
        UUID docId = upload(tokenA, "grande.txt", "text/plain", texto.getBytes()).id();
        aguardarReady(docId, tokenA);

        SearchResponse[] k3 = buscar(tokenA, "texto longo", 3);
        assertThat(k3.length).isLessThanOrEqualTo(3);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SearchResponse[] buscar(String token, String query, int k) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<SearchResponse[]> resp = restTemplate.exchange(
                "/api/search?q=" + query + "&k=" + k,
                HttpMethod.GET, new HttpEntity<>(h), SearchResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private DocumentResponse upload(String token, String filename, String contentType, byte[] content) {
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

    private void aguardarReady(UUID docId, String token) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(getStatus(docId, token)).isEqualTo("READY"));
    }

    private String getStatus(UUID docId, String token) {
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
