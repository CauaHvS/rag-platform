package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.document.DocumentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para o endpoint de streaming SSE.
 *
 * Usa Java HttpClient diretamente para consumir text/event-stream,
 * já que TestRestTemplate não tem conversor para esse content type.
 * BodyHandlers.ofLines() lê a resposta de forma lazy até a conexão fechar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatStreamIT {

    @LocalServerPort
    int port;

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

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** RestTemplate simples (HttpURLConnection) para leitura bloqueante de SSE. */
    private RestTemplate sseRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }
    private String token;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        token = registrarELogar("Stream", "stream+" + suffix + "@chat.com", "senha1234S");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void stream_comDocumento_retornaSourcesTokenDone() {
        String texto = "Redes neurais artificiais são modelos computacionais inspirados "
                + "no cérebro humano.".repeat(10);
        UUID docId = upload("rnn.txt", "text/plain", texto.getBytes()).id();
        aguardarReady(docId);

        String body = ssePost("{\"question\":\"O que são redes neurais?\",\"k\":3}");

        assertThat(body).contains("event:sources");
        assertThat(body).contains("event:token");
        assertThat(body).contains("event:done");
        // Fontes devem ser JSON de array não-vazio (documento carregado)
        assertThat(body).containsPattern("data:\\[\\{");
    }

    @Test
    void stream_semDocumentos_retornaSourcesVazioETokens() {
        String body = ssePost("{\"question\":\"Pergunta sem contexto\",\"k\":5}");

        assertThat(body).contains("event:sources");
        assertThat(body).contains("data:[]");   // array vazio de fontes
        assertThat(body).contains("event:token");
        assertThat(body).contains("event:done");
    }

    @Test
    void stream_semToken_retorna401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url("/api/chat/stream"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"teste\"}"))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void stream_questaoVazia_retorna400() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(url("/api/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"\"}"))
                .build();

        int status = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        assertThat(status).isEqualTo(400);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * POST /api/chat/stream e retorna o body SSE completo como String.
     * Usa RestTemplate + HttpURLConnection que lê bytes até a conexão fechar —
     * compatível com SseEmitter sem os quirks do Java HttpClient.ofLines().
     */
    private String ssePost(String jsonBody) {
        return sseRestTemplate().execute(
                "http://localhost:" + port + "/api/chat/stream",
                HttpMethod.POST,
                req -> {
                    req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    req.getHeaders().set("Authorization", "Bearer " + token);
                    req.getBody().write(jsonBody.getBytes(StandardCharsets.UTF_8));
                },
                resp -> new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
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
