package dev.ragplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ragplatform.infrastructure.web.auth.AuthResponse;
import dev.ragplatform.infrastructure.web.document.DocumentResponse;
import dev.ragplatform.infrastructure.web.search.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Avaliação de qualidade de recuperação RAG com golden set.
 *
 * Métricas calculadas:
 *   Recall@k  — fração de queries onde o documento correto aparece nos top-k resultados.
 *   MRR       — Mean Reciprocal Rank: média de 1/rank do primeiro resultado correto.
 *               MRR=1.0 significa que o correto sempre aparece em primeiro lugar.
 *
 * Golden set: src/test/resources/golden/dataset.json (3 documentos, 5 queries).
 * Com FakeEmbeddingProvider (vetores zero) + modo hybrid, o BM25 full-text garante
 * recall determinístico para termos exatos presentes nos documentos.
 *
 * Thresholds mínimos (falham o build se não atingidos):
 *   Recall@5 >= 0.8
 *   MRR      >= 0.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EvaluationIT {

    private static final Logger log = LoggerFactory.getLogger(EvaluationIT.class);

    private static final int K = 5;
    private static final double MIN_RECALL = 0.8;
    private static final double MIN_MRR    = 0.6;

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
        token = registrarELogar("Evaluator", "eval+" + suffix + "@golden.com", "Senha1234S");
    }

    // ── Avaliação ───────────────────────────────────────────────────────────

    /**
     * Carrega o golden set, sobe todos os documentos, aguarda READY
     * e avalia recall@5 e MRR para o modo híbrido.
     */
    @Test
    void avaliacao_hibrida_recall_e_mrr_atingem_thresholds() throws Exception {
        Map<String, UUID> docIdMap = carregarDocumentos();

        EvaluationResult hybrid = avaliar(docIdMap, "hybrid");

        log.info("=== Avaliação (hybrid) ===");
        log.info("Recall@{} = {} (mínimo: {})", K, String.format("%.3f", hybrid.recall()), MIN_RECALL);
        log.info("MRR       = {} (mínimo: {})", String.format("%.3f", hybrid.mrr()), MIN_MRR);
        log.info("Acertos   = {}/{}", hybrid.hits(), hybrid.total());
        hybrid.details().forEach(d ->
                log.info("  [{}] rank={} correto={} | {}", d.queryId(), d.rank(), d.correct(), d.question()));

        assertThat(hybrid.recall())
                .as("Recall@%d (hybrid) deve ser >= %.1f, obtido: %.3f", K, MIN_RECALL, hybrid.recall())
                .isGreaterThanOrEqualTo(MIN_RECALL);

        assertThat(hybrid.mrr())
                .as("MRR (hybrid) deve ser >= %.1f, obtido: %.3f", MIN_MRR, hybrid.mrr())
                .isGreaterThanOrEqualTo(MIN_MRR);
    }

    /**
     * Compara hybrid vs vector: com FakeEmbeddingProvider (vetores zero),
     * o recall híbrido deve ser >= recall vetorial puro.
     */
    @Test
    void avaliacao_hibrida_recall_maior_ou_igual_ao_vetorial() throws Exception {
        Map<String, UUID> docIdMap = carregarDocumentos();

        EvaluationResult hybrid = avaliar(docIdMap, "hybrid");
        EvaluationResult vector = avaliar(docIdMap, "vector");

        log.info("Recall hybrid={} | vector={}", String.format("%.3f", hybrid.recall()),
                String.format("%.3f", vector.recall()));
        log.info("MRR    hybrid={} | vector={}", String.format("%.3f", hybrid.mrr()),
                String.format("%.3f", vector.mrr()));

        assertThat(hybrid.recall())
                .as("Recall híbrido >= vetorial (FTS compensa embeddings fake)")
                .isGreaterThanOrEqualTo(vector.recall());
    }

    // ── Setup: carrega documentos do golden set ───────────────────────────────

    private Map<String, UUID> carregarDocumentos() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataset = mapper.readTree(new ClassPathResource("golden/dataset.json").getInputStream());

        Map<String, UUID> docIdMap = new LinkedHashMap<>();
        for (JsonNode doc : dataset.get("documents")) {
            String goldenId = doc.get("id").asText();
            String filename = doc.get("filename").asText();
            String content  = doc.get("content").asText();
            UUID realId = upload(filename, content).id();
            docIdMap.put(goldenId, realId);
        }
        docIdMap.values().forEach(this::aguardarReady);
        return docIdMap;
    }

    // ── Cálculo das métricas ─────────────────────────────────────────────────

    private EvaluationResult avaliar(Map<String, UUID> docIdMap, String mode) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataset = mapper.readTree(new ClassPathResource("golden/dataset.json").getInputStream());

        List<QueryDetail> details = new ArrayList<>();
        int total = 0;
        int hits = 0;
        double reciprocalRankSum = 0.0;

        for (JsonNode query : dataset.get("queries")) {
            String queryId      = query.get("id").asText();
            String question     = query.get("question").asText();
            String goldenDoc    = query.get("documentId").asText();
            UUID   expectedDocId = docIdMap.get(goldenDoc);

            List<SearchResponse> results = search(question, K, mode);

            int rank = -1;
            for (int i = 0; i < results.size(); i++) {
                if (expectedDocId.equals(results.get(i).documentId())) {
                    rank = i + 1;
                    break;
                }
            }

            boolean correct = rank > 0;
            if (correct) {
                hits++;
                reciprocalRankSum += 1.0 / rank;
            }
            total++;
            details.add(new QueryDetail(queryId, question, rank, correct));
        }

        double recall = total > 0 ? (double) hits / total : 0.0;
        double mrr    = total > 0 ? reciprocalRankSum / total : 0.0;
        return new EvaluationResult(recall, mrr, hits, total, details);
    }

    record EvaluationResult(double recall, double mrr, int hits, int total, List<QueryDetail> details) {}
    record QueryDetail(String queryId, String question, int rank, boolean correct) {}

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<SearchResponse> search(String query, int k, String mode) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String encodedQuery;
        try {
            encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
        } catch (Exception e) {
            encodedQuery = query;
        }
        return restTemplate.exchange(
                "/api/search?q=" + encodedQuery + "&k=" + k + "&mode=" + mode,
                HttpMethod.GET,
                new HttpEntity<>(h),
                new ParameterizedTypeReference<List<SearchResponse>>() {}
        ).getBody();
    }

    private DocumentResponse upload(String filename, String content) {
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
                "/api/documents", HttpMethod.POST,
                new HttpEntity<>(body, h), DocumentResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return resp.getBody();
    }

    private void aguardarReady(UUID docId) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    HttpHeaders h = new HttpHeaders();
                    h.setBearerAuth(token);
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
