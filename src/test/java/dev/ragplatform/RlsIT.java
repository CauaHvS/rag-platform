package dev.ragplatform;

import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.document.DocumentResponse;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para Row Level Security (RLS).
 *
 * Prova defesa em profundidade: mesmo sem o filtro owner_id no WHERE,
 * o Postgres bloqueia acesso entre usuários via política RLS.
 *
 * Usa TransactionTemplate + JdbcTemplate com SET LOCAL ROLE ragplatform_app
 * para simular o contexto de usuário não-superuser da produção.
 *
 * NOTA SOBRE SUPERUSER:
 * POSTGRES_USER na imagem Docker oficial cria um superuser, que ignora RLS
 * mesmo com FORCE ROW LEVEL SECURITY. Em testes, usamos SET LOCAL ROLE
 * ragplatform_app (role NOSUPERUSER criado em V8) para provar as políticas.
 * Em produção, a aplicação conecta como ragplatform_app (não-superuser).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RlsIT {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String tokenA;
    private UUID userAId;
    private UUID docAId;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tokenA = registrar("userA+" + suffix + "@rls.com", "Senha1234S");
        userAId = getUserId(tokenA);

        String conteudo = "Documento confidencial do usuário A com informações sigilosas. "
                .repeat(20);
        docAId = upload(tokenA, "sigiloso.txt", conteudo).id();
        aguardarReady(tokenA, docAId);
    }

    @AfterEach
    void limparContextoRls() {
        // Garante que o contexto RLS não vaza entre testes via conexão do pool
        jdbc.execute("SET app.current_user_id = ''");
    }

    // ── Testes ───────────────────────────────────────────────────────────────

    @Test
    void sem_usuario_definido_rls_bypassa_e_linhas_sao_visiveis() {
        // app.current_user_id vazio: política RLS retorna TRUE para todas as linhas.
        // Simula jobs de background (ingestão, indexação) que rodam sem contexto de usuário.
        Integer count = tx.execute(s -> {
            jdbc.execute("SET LOCAL ROLE ragplatform_app");
            jdbc.execute("SET LOCAL app.current_user_id = ''");
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE id = ?",
                    Integer.class, docAId);
        });
        // Com userId vazio, política permite → documento visível
        assertThat(count).isOne();
    }

    @Test
    void usuario_a_ve_seus_proprios_documentos_via_rls() {
        // Com app.current_user_id = userAId, política retorna TRUE apenas para linhas de A.
        Integer count = tx.execute(s -> {
            jdbc.execute("SET LOCAL ROLE ragplatform_app");
            jdbc.execute("SET LOCAL app.current_user_id = '" + userAId + "'");
            // Sem WHERE clause: apenas RLS decide quais linhas retornar
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE id = ?",
                    Integer.class, docAId);
        });
        assertThat(count).isOne();
    }

    @Test
    void usuario_b_nao_ve_documentos_de_usuario_a_sem_where_clause() {
        // Prova defesa em profundidade: sem WHERE owner_id no SQL,
        // o RLS no banco ainda impede que B veja documentos de A.
        String suffixB = UUID.randomUUID().toString().substring(0, 8);
        registrar("userB+" + suffixB + "@rls.com", "Senha1234S");
        UUID userBId = getUserIdByEmail("userB+" + suffixB + "@rls.com");

        Integer count = tx.execute(s -> {
            // Switch para role não-superuser: RLS passa a ser aplicado
            jdbc.execute("SET LOCAL ROLE ragplatform_app");
            jdbc.execute("SET LOCAL app.current_user_id = '" + userBId + "'");
            // SELECT sem WHERE: apenas RLS decide quais linhas ver
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE id = ?",
                    Integer.class, docAId);
        });
        // RLS bloqueia: B não vê o documento de A
        assertThat(count).isZero();
    }

    @Test
    void chunks_de_usuario_a_nao_sao_visiveis_para_usuario_b() {
        // Verifica isolamento na tabela chunks (crítica para RAG: vazamento aqui
        // significa contexto de terceiros no prompt do LLM).
        String suffixB = UUID.randomUUID().toString().substring(0, 8);
        registrar("userB+" + suffixB + "@rls.com", "Senha1234S");
        UUID userBId = getUserIdByEmail("userB+" + suffixB + "@rls.com");

        // Com contexto de A: chunks de A visíveis
        Integer countA = tx.execute(s -> {
            jdbc.execute("SET LOCAL ROLE ragplatform_app");
            jdbc.execute("SET LOCAL app.current_user_id = '" + userAId + "'");
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chunks WHERE document_id = ?",
                    Integer.class, docAId);
        });
        assertThat(countA).isPositive();

        // Com contexto de B: chunks de A invisíveis
        Integer countB = tx.execute(s -> {
            jdbc.execute("SET LOCAL ROLE ragplatform_app");
            jdbc.execute("SET LOCAL app.current_user_id = '" + userBId + "'");
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chunks WHERE document_id = ?",
                    Integer.class, docAId);
        });
        assertThat(countB).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String registrar(String email, String password) {
        restTemplate.postForEntity("/auth/register",
                new HttpEntity<>(Map.of("name", "Teste", "email", email, "password", password),
                        jsonHeaders()),
                AuthResponse.class);
        return restTemplate.postForEntity("/auth/login",
                new HttpEntity<>(Map.of("email", email, "password", password), jsonHeaders()),
                AuthResponse.class).getBody().token();
    }

    private UUID getUserId(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String idStr = (String) restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(h), Map.class)
                .getBody().get("id");
        return UUID.fromString(idStr);
    }

    private UUID getUserIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private DocumentResponse upload(String token, String filename, String content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token);

        var resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        };
        var part = new HttpEntity<>(resource, singleHeader(HttpHeaders.CONTENT_TYPE, "text/plain"));
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", part);

        ResponseEntity<DocumentResponse> resp = restTemplate.exchange(
                "/api/documents", HttpMethod.POST, new HttpEntity<>(body, h), DocumentResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return resp.getBody();
    }

    private void aguardarReady(String token, UUID docId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(token);
                    String status = restTemplate.exchange(
                            "/api/documents/" + docId, HttpMethod.GET,
                            new HttpEntity<>(h), DocumentResponse.class)
                            .getBody().status();
                    assertThat(status).isEqualTo("READY");
                });
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
