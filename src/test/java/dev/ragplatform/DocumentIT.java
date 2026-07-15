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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DocumentIT {

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

    @Autowired
    TestRestTemplate restTemplate;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setup() {
        tokenA = registrarELogar("Alice", "alice@example.com", "senha1234A");
        tokenB = registrarELogar("Bob", "bob@example.com", "senha1234B");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void uploadValido_retorna202_comStatusPending() {
        var response = upload(tokenA, "contrato.txt", "text/plain", "Conteúdo do contrato.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.status()).isEqualTo("PENDING");
        assertThat(body.originalName()).isEqualTo("contrato.txt");
    }

    @Test
    void uploadSemToken_retorna401() {
        var response = upload(null, "doc.txt", "text/plain", "texto");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void uploadTipoNaoSuportado_retorna415() {
        var response = upload(tokenA, "imagem.png", "image/png", "dados binários");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void listagemRetornaApenasDocumentosDoProprioUsuario() {
        // Usuário A sobe um documento
        var uploadResp = upload(tokenA, "relatorio.txt", "text/plain", "Relatório confidencial.");
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Usuário A lista — vê o documento
        var listaA = listar(tokenA);
        assertThat(listaA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listaA.getBody()).isNotNull().isNotEmpty();

        // ISOLAMENTO: usuário B lista — não vê nada de A
        var listaB = listar(tokenB);
        assertThat(listaB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listaB.getBody()).isNotNull().isEmpty();
    }

    @Test
    void buscarPorId_usuarioDono_retorna200() {
        var uploadResp = upload(tokenA, "lei.txt", "text/plain", "Art. 1º...");
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var docId = uploadResp.getBody().id();

        var getResp = buscarPorId(tokenA, docId);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().id()).isEqualTo(docId);
    }

    @Test
    void buscarPorId_outroUsuario_retorna404_semVazarContexto() {
        // A sobe o arquivo
        var uploadResp = upload(tokenA, "segredo.txt", "text/plain", "Dados sigilosos.");
        var docId = uploadResp.getBody().id();

        // ISOLAMENTO: B tenta acessar o documento de A — deve receber 404 (não 403, para não revelar existência)
        var getResp = buscarPorId(tokenB, docId);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<DocumentResponse> upload(String token, String filename,
                                                     String contentType, String content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) headers.setBearerAuth(token);

        var fileResource = new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() { return filename; }
        };

        var part = new HttpEntity<>(fileResource, singleHeader(HttpHeaders.CONTENT_TYPE, contentType));
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", part);

        return restTemplate.exchange(
                "/api/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), DocumentResponse.class);
    }

    private ResponseEntity<DocumentResponse[]> listar(String token) {
        return restTemplate.exchange(
                "/api/documents", HttpMethod.GET,
                comToken(token), DocumentResponse[].class);
    }

    private ResponseEntity<DocumentResponse> buscarPorId(String token, java.util.UUID id) {
        return restTemplate.exchange(
                "/api/documents/" + id, HttpMethod.GET,
                comToken(token), DocumentResponse.class);
    }

    private HttpEntity<Void> comToken(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }

    private HttpHeaders singleHeader(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.set(name, value);
        return h;
    }

    /** Registra o usuário (ignora 409 se já existir) e retorna o token. */
    private String registrarELogar(String name, String email, String password) {
        restTemplate.postForEntity("/auth/register",
                new HttpEntity<>(Map.of("name", name, "email", email, "password", password),
                        jsonHeaders()),
                AuthResponse.class);

        var loginResp = restTemplate.postForEntity("/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()),
                AuthResponse.class);
        return loginResp.getBody().token();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
