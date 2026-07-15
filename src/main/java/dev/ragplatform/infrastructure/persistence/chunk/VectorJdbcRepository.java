package dev.ragplatform.infrastructure.persistence.chunk;

import dev.ragplatform.domain.model.SimilarChunk;
import dev.ragplatform.domain.port.out.VectorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador de busca vetorial usando JdbcTemplate e PgVector.
 *
 * Usa PreparedStatementCreator com casts explícitos ::vector e ::uuid no SQL.
 * Isso contorna limitações do driver JDBC que não infere o tipo "vector"
 * automaticamente para parâmetros String (CAST(? AS vector) com setString
 * é resolvido incorretamente como VARCHAR vs o tipo interno vector).
 *
 * O operador <=> calcula distância coseno (0 = idêntico, 2 = oposto).
 * Filtra sempre por owner_id para garantir isolamento multiusuário.
 */
@Repository
public class VectorJdbcRepository implements VectorRepository {

    private final JdbcTemplate jdbc;

    public VectorJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveEmbedding(UUID chunkId, float[] embedding) {
        String vectorStr = toVectorString(embedding);
        jdbc.update(conn -> {
            var ps = conn.prepareStatement(
                    "UPDATE chunks SET embedding = ?::vector WHERE id = ?::uuid");
            ps.setString(1, vectorStr);
            ps.setString(2, chunkId.toString());
            return ps;
        });
    }

    @Override
    public List<SimilarChunk> findSimilar(UUID ownerId, float[] queryEmbedding, int k) {
        String vectorStr = toVectorString(queryEmbedding);
        return jdbc.query(conn -> {
            var ps = conn.prepareStatement("""
                    SELECT c.id,
                           c.document_id,
                           c.content,
                           c.char_start,
                           c.char_end,
                           1.0 - (c.embedding <=> ?::vector) AS similarity
                    FROM chunks c
                    WHERE c.owner_id = ?::uuid
                      AND c.embedding IS NOT NULL
                    ORDER BY c.embedding <=> ?::vector
                    LIMIT ?
                    """);
            ps.setString(1, vectorStr);
            ps.setString(2, ownerId.toString());
            ps.setString(3, vectorStr);
            ps.setInt(4, k);
            return ps;
        }, (rs, rowNum) -> new SimilarChunk(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                rs.getString("content"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                rs.getDouble("similarity")
        ));
    }

    /**
     * Busca híbrida via RRF (Reciprocal Rank Fusion).
     *
     * Combina dois rankings independentes:
     *   - vetorial: distância coseno (<=>)
     *   - full-text: ts_rank_cd sobre coluna content_tsv (tsvector gerada)
     *
     * RRF score = 1/(60 + rank_vec) + 1/(60 + rank_fts)
     * FULL OUTER JOIN garante que chunks presentes em apenas um dos rankings
     * ainda contribuem (com score parcial).
     *
     * Over-fetch (k*3) em cada subquery dá ao RRF mais candidatos para reranquear.
     */
    @Override
    public List<SimilarChunk> findSimilarHybrid(UUID ownerId, float[] queryEmbedding, String query, int k) {
        String vectorStr = toVectorString(queryEmbedding);
        int overfetch = k * 3;
        return jdbc.query(conn -> {
            var ps = conn.prepareStatement("""
                    WITH vector_ranked AS (
                        SELECT id,
                               ROW_NUMBER() OVER (ORDER BY embedding <=> ?::vector) AS rnk
                        FROM chunks
                        WHERE owner_id = ?::uuid
                          AND embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT ?
                    ),
                    text_ranked AS (
                        SELECT id,
                               ROW_NUMBER() OVER (
                                   ORDER BY ts_rank_cd(content_tsv,
                                       plainto_tsquery('portuguese', ?)) DESC
                               ) AS rnk
                        FROM chunks
                        WHERE owner_id = ?::uuid
                          AND content_tsv @@ plainto_tsquery('portuguese', ?)
                        LIMIT ?
                    ),
                    rrf_scores AS (
                        SELECT
                            COALESCE(v.id, t.id)                           AS chunk_id,
                            COALESCE(1.0 / (60.0 + v.rnk), 0.0)
                            + COALESCE(1.0 / (60.0 + t.rnk), 0.0)         AS rrf_score
                        FROM vector_ranked v
                        FULL OUTER JOIN text_ranked t ON v.id = t.id
                    )
                    SELECT c.id, c.document_id, c.content, c.char_start, c.char_end,
                           r.rrf_score AS similarity
                    FROM rrf_scores r
                    JOIN chunks c ON c.id = r.chunk_id
                    ORDER BY r.rrf_score DESC
                    LIMIT ?
                    """);
            // vector_ranked: embedding, ownerId, embedding (ORDER BY), overfetch
            ps.setString(1, vectorStr);
            ps.setString(2, ownerId.toString());
            ps.setString(3, vectorStr);
            ps.setInt(4, overfetch);
            // text_ranked: query, ownerId, query (@@), overfetch
            ps.setString(5, query);
            ps.setString(6, ownerId.toString());
            ps.setString(7, query);
            ps.setInt(8, overfetch);
            // final LIMIT
            ps.setInt(9, k);
            return ps;
        }, (rs, rowNum) -> new SimilarChunk(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                rs.getString("content"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                rs.getDouble("similarity")
        ));
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
