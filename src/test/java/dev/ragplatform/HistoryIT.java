package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.chat.ChatTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de integração para GET /api/chat/history.
 *
 * Verifica:
 * - histórico retorna turnos do usuário após chat síncrono
 * - isolamento: usuário A não vê turnos de usuário B
 * - histórico vazio retorna lista vazia
 * - sem token retorna 401
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HistoryIT {

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
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tokenA = registrarELogar("Alice", "alice+" + suffix + "@hist.com", "Senha1234S");
        tokenB = registrarELogar("Bob", "bob+" + suffix + "@hist.com", "Senha1234S");
    }

    @Test
    void history_semTurnos_retornaListaVazia() {
        List<ChatTurnResponse> history = getHistory(tokenA);
        assertThat(history).isEmpty();
    }

    @Test
    void history_aposChat_retornaOTurno() {
        chat(tokenA, "O que é RAG?");

        List<ChatTurnResponse> history = getHistory(tokenA);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).question()).isEqualTo("O que é RAG?");
        assertThat(history.get(0).answer()).isNotBlank();
        assertThat(history.get(0).createdAt()).isNotNull();
    }

    @Test
    void history_isolamentoEntreUsuarios() {
        chat(tokenA, "Pergunta do usuário A");

        // B não deve ver turnos de A
        List<ChatTurnResponse> historyB = getHistory(tokenB);
        assertThat(historyB).isEmpty();

        // A vê o próprio turno
        List<ChatTurnResponse> historyA = getHistory(tokenA);
        assertThat(historyA).hasSize(1);
    }

    @Test
    void history_multiplosChats_retornaOrdemDecrescente() {
        chat(tokenA, "Primeira pergunta");
        chat(tokenA, "Segunda pergunta");

        List<ChatTurnResponse> history = getHistory(tokenA);
        assertThat(history).hasSize(2);
        // Mais recente primeiro
        assertThat(history.get(0).question()).isEqualTo("Segunda pergunta");
        assertThat(history.get(1).question()).isEqualTo("Primeira pergunta");
    }

    @Test
    void history_semToken_retorna401() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/chat/history", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void chat(String token, String question) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        restTemplate.exchange("/api/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", question), h), String.class);
    }

    private List<ChatTurnResponse> getHistory(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<List<ChatTurnResponse>> resp = restTemplate.exchange(
                "/api/chat/history", HttpMethod.GET,
                new HttpEntity<>(h),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
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
