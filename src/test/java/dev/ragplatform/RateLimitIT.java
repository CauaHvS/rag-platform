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
 * Testes de integração para rate limiting de /api/chat/**.
 *
 * Usa limit=2 (via DynamicPropertySource) para verificar que a 3ª requisição
 * recebe 429 sem precisar fazer dezenas de chamadas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitIT {

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
        // Limite baixo para testar sem fazer muitas requisições
        registry.add("app.ratelimit.chat.max-per-minute", () -> "2");
    }

    @Autowired TestRestTemplate restTemplate;

    private String token;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        token = registrarELogar("RateUser", "rate+" + suffix + "@test.com", "Senha1234S");
    }

    @Test
    void terceira_requisicao_retorna_429() {
        // Duas primeiras devem passar (200)
        assertThat(chat("Pergunta 1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat("Pergunta 2").getStatusCode()).isEqualTo(HttpStatus.OK);

        // Terceira deve ser bloqueada
        ResponseEntity<String> terceira = chat("Pergunta 3");
        assertThat(terceira.getStatusCode().value()).isEqualTo(429);
        assertThat(terceira.getBody()).contains("rate-limit");
    }

    @Test
    void rate_limit_isolado_por_usuario() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tokenB = registrarELogar("UserB", "rateb+" + suffix + "@test.com", "Senha1234S");

        // Usuário A esgota o limite
        chat("Pergunta A1");
        chat("Pergunta A2");
        ResponseEntity<String> aLimitado = chat("Pergunta A3");
        assertThat(aLimitado.getStatusCode().value()).isEqualTo(429);

        // Usuário B ainda pode fazer chamadas
        ResponseEntity<String> bOk = chatCom(tokenB, "Pergunta B1");
        assertThat(bOk.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void sem_token_retorna_401_nao_429() {
        ResponseEntity<String> resp = restTemplate.postForEntity("/api/chat",
                new HttpEntity<>(Map.of("question", "teste"), jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<String> chat(String question) {
        return chatCom(token, question);
    }

    private ResponseEntity<String> chatCom(String tok, String question) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(tok);
        return restTemplate.postForEntity("/api/chat",
                new HttpEntity<>(Map.of("question", question), h), String.class);
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
