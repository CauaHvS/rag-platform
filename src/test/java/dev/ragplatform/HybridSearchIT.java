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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para busca híbrida (vetorial + BM25 full-text via RRF).
 *
 * Com FakeEmbeddingProvider (vetores zero), o ranking vetorial é indiferente.
 * Isso permite testar o contributo do FTS de forma isolada:
 * o chunk com o termo exato deve aparecer primeiro no modo hybrid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HybridSearchIT {

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
        token = registrarELogar("Hybrid", "hybrid+" + suffix + "@test.com", "Senha1234S");
    }

    // ── Testes ──────────────────────────────────────────────────────────────

    @Test
    void hybrid_encontra_chunk_por_termo_exato_no_texto() {
        // Documento A: contém o termo "fotossíntese"
        String conteudoA = "Fotossíntese é o processo pelo qual plantas convertem "
                + "luz solar em energia química armazenada na forma de glicose. "
                + "Cloroplastos são as organelas responsáveis por este processo vital.".repeat(5);
        UUID docA = upload("fotossintese.txt", conteudoA).id();
        aguardarReady(docA);

        // Documento B: sobre outro assunto
        String conteudoB = "O motor de combustão interna converte energia química "
                + "do combustível em trabalho mecânico através de explosões controladas. "
                + "Pistões e cilindros são componentes essenciais do motor.".repeat(5);
        UUID docB = upload("motor.txt", conteudoB).id();
        aguardarReady(docB);

        // Busca híbrida pelo termo "fotossíntese" → deve retornar do documento A primeiro
        List<SearchResponse> results = search("fotossíntese", 5, "hybrid");

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).documentId()).isEqualTo(docA);
    }

    @Test
    void hybrid_diferencia_documentos_por_termos_distintos() {
        String sufixo = UUID.randomUUID().toString().substring(0, 4);
        String termA = "neuroplasticidade" + sufixo;
        String termB = "termodinâmica" + sufixo;

        String conteudoA = ("A " + termA + " é a capacidade do cérebro de reorganizar "
                + "conexões sinápticas em resposta a novas experiências de aprendizado.").repeat(8);
        String conteudoB = ("A " + termB + " estuda a transformação de energia e calor "
                + "em sistemas físicos, descrita pelas quatro leis fundamentais.").repeat(8);

        UUID docA = upload("neuro.txt", conteudoA).id();
        UUID docB = upload("termo.txt", conteudoB).id();
        aguardarReady(docA);
        aguardarReady(docB);

        List<SearchResponse> resultsA = search(termA, 3, "hybrid");
        assertThat(resultsA).isNotEmpty();
        assertThat(resultsA.get(0).documentId()).isEqualTo(docA);

        List<SearchResponse> resultsB = search(termB, 3, "hybrid");
        assertThat(resultsB).isNotEmpty();
        assertThat(resultsB.get(0).documentId()).isEqualTo(docB);
    }

    @Test
    void modoVector_e_modoHybrid_ambos_retornam_resultados() {
        String conteudo = "Aprendizado de máquina utiliza algoritmos para permitir "
                + "que computadores aprendam padrões a partir de dados históricos.".repeat(8);
        UUID docId = upload("ml.txt", conteudo).id();
        aguardarReady(docId);

        List<SearchResponse> hybrid = search("aprendizado", 5, "hybrid");
        List<SearchResponse> vector = search("aprendizado", 5, "vector");

        // Ambos os modos devem retornar resultados (existência de chunks)
        assertThat(hybrid).isNotEmpty();
        assertThat(vector).isNotEmpty();

        // Modo híbrido deve ter o documento correto no topo (FTS rankeia por termo exato)
        assertThat(hybrid.get(0).documentId()).isEqualTo(docId);
    }

    @Test
    void hybrid_isolamento_usuario_nao_vaza_chunks() {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String tokenB = registrarELogar("UserB", "userb+" + sufixo + "@hybrid.com", "Senha1234S");

        // Usuário B sobe documento com termo único
        String termoUnico = "xkzqw" + sufixo;
        String conteudoB = ("Termo secreto " + termoUnico + " não deve aparecer para outro usuário.").repeat(8);
        UUID docB = uploadCom(tokenB, "secreto.txt", conteudoB).id();
        aguardarReadyCom(tokenB, docB);

        // Usuário A (token principal) busca esse termo
        List<SearchResponse> resultados = search(termoUnico, 5, "hybrid");

        // A não pode ver documentos de B
        assertThat(resultados).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<SearchResponse> search(String query, int k, String mode) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/search?q=" + query + "&k=" + k + "&mode=" + mode,
                HttpMethod.GET,
                new HttpEntity<>(h),
                new ParameterizedTypeReference<List<SearchResponse>>() {}
        ).getBody();
    }

    private DocumentResponse upload(String filename, String content) {
        return uploadCom(token, filename, content);
    }

    private DocumentResponse uploadCom(String tok, String filename, String content) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(tok);

        var resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        };
        var part = new HttpEntity<>(resource, singleHeader(HttpHeaders.CONTENT_TYPE, "text/plain"));
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", part);

        ResponseEntity<DocumentResponse> resp = restTemplate.exchange(
                "/api/documents", HttpMethod.POST,
                new HttpEntity<>(body, h), DocumentResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return resp.getBody();
    }

    private void aguardarReady(UUID docId) {
        aguardarReadyCom(token, docId);
    }

    private void aguardarReadyCom(String tok, UUID docId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(tok);
                    String status = restTemplate.exchange(
                            "/api/documents/" + docId, HttpMethod.GET,
                            new HttpEntity<>(h), DocumentResponse.class).getBody().status();
                    assertThat(status).isEqualTo("READY");
                });
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
