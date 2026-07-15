package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.auth.LoginRequest;
import dev.ragplatform.infrastructure.web.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate rest;

    // ── Registro ──────────────────────────────────────────────────────────────

    @Test
    void registroComDadosValidosRetorna201ComToken() {
        var request = new RegisterRequest("Maria Souza", "maria@teste.com", "senha1234");

        ResponseEntity<AuthResponse> response =
                rest.postForEntity("/auth/register", request, AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().user().email()).isEqualTo("maria@teste.com");
    }

    @Test
    void registroComEmailDuplicadoRetorna409() {
        var request = new RegisterRequest("Pedro Lima", "pedro@teste.com", "senha1234");
        rest.postForEntity("/auth/register", request, Void.class);

        ResponseEntity<String> response =
                rest.postForEntity("/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registroComEmailInvalidoRetorna400ComErrosDeCampo() {
        var request = new RegisterRequest("A", "nao-e-email", "curta");

        ResponseEntity<String> response =
                rest.postForEntity("/auth/register", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("errors");
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void loginComCredenciaisValidasRetorna200ComToken() {
        rest.postForEntity("/auth/register",
                new RegisterRequest("Ana Costa", "ana@teste.com", "senha1234"), Void.class);

        ResponseEntity<AuthResponse> response = rest.postForEntity("/auth/login",
                new LoginRequest("ana@teste.com", "senha1234"), AuthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
    }

    @Test
    void loginComSenhaErradaRetorna401() {
        rest.postForEntity("/auth/register",
                new RegisterRequest("Carlos Dias", "carlos@teste.com", "senha1234"), Void.class);

        ResponseEntity<String> response = rest.postForEntity("/auth/login",
                new LoginRequest("carlos@teste.com", "senhaErrada"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Rota protegida ────────────────────────────────────────────────────────

    @Test
    void rotaProtegidaSemTokenRetorna401() {
        ResponseEntity<String> response = rest.getForEntity("/api/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rotaProtegidaComTokenValidoRetorna200() {
        // Registra e captura o token
        ResponseEntity<AuthResponse> reg = rest.postForEntity("/auth/register",
                new RegisterRequest("Lucia Mendes", "lucia@teste.com", "senha1234"), AuthResponse.class);
        String token = reg.getBody().token();

        // Acessa /api/me com o token
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<AuthResponse.UserInfo> response = rest.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(headers), AuthResponse.UserInfo.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().email()).isEqualTo("lucia@teste.com");
    }
}
