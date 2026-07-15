package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.chat.ChatResponse;
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
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatIT {

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
        token = registrarELogar("User", "user+" + suffix + "@chat.com", "senha1234U");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void chatComDocumento_retornaRespostaEFontes() {
        String texto = "Aprendizado de máquina é um subcampo da inteligência artificial "
                .repeat(20);
        UUID docId = upload("ml.txt", "text/plain", texto.getBytes()).id();
        aguardarReady(docId);

        var resp = chat("O que é aprendizado de máquina?", 5);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().answer()).isNotBlank();
        assertThat(resp.getBody().sources()).isNotEmpty();
    }

    @Test
    void chatSemDocumentos_retornaRespostaSemFontes() {
        var resp = chat("Quais são meus documentos?", 5);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().answer()).isNotBlank();
        assertThat(resp.getBody().sources()).isEmpty();
    }

    @Test
    void chatSemToken_retorna401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        var resp = restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "teste"), h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void chatQuestaoVazia_retorna400() {
        var resp = restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", ""), jsonBearerHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void chatKAcimaDo20_retorna400() {
        var body = Map.of("question", "pergunta válida", "k", 99);
        var resp = restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(body, jsonBearerHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<ChatResponse> chat(String question, int k) {
        var body = Map.of("question", question, "k", k);
        return restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(body, jsonBearerHeaders()), ChatResponse.class);
    }

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

    private void aguardarReady(UUID docId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(getStatus(docId)).isEqualTo("READY"));
    }

    private String getStatus(UUID docId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange("/api/documents/" + docId, HttpMethod.GET,
                new HttpEntity<>(h), DocumentResponse.class).getBody().status();
    }

    private HttpHeaders jsonBearerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
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
