package com.kubeoncall.evidence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Durable structured conclusions associated with one workflow execution. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class ConclusionRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SopEvidenceReference>> SOP_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConclusionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsert(String executionPublicId, AiConclusion conclusion) {
        int updated = jdbcTemplate.update(
                """
                INSERT INTO koc_ai_conclusion
                  (public_id, execution_id, claim_text, severity, support_status,
                   evidence_refs_json, sop_refs_json, confidence_score, confidence_label,
                   confidence_basis_json, planner_json, recommended_action_json)
                SELECT ?, e.id, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, CAST(? AS JSON),
                       CAST(? AS JSON), CAST(? AS JSON)
                  FROM koc_workflow_execution e
                 WHERE e.public_id = ?
                ON DUPLICATE KEY UPDATE
                  claim_text = VALUES(claim_text),
                  severity = VALUES(severity),
                  support_status = VALUES(support_status),
                  evidence_refs_json = VALUES(evidence_refs_json),
                  sop_refs_json = VALUES(sop_refs_json),
                  confidence_score = VALUES(confidence_score),
                  confidence_label = VALUES(confidence_label),
                  confidence_basis_json = VALUES(confidence_basis_json),
                  planner_json = VALUES(planner_json),
                  recommended_action_json = VALUES(recommended_action_json)
                """,
                conclusion.conclusionId(),
                conclusion.claim(),
                conclusion.severity(),
                conclusion.status(),
                json(conclusion.evidenceRefs()),
                json(conclusion.sopRefs()),
                conclusion.confidence().score(),
                conclusion.confidence().label(),
                json(conclusion.confidence().basis()),
                json(conclusion.planner()),
                json(conclusion.recommendedAction()),
                executionPublicId);
        if (updated == 0 && !executionExists(executionPublicId)) {
            throw new IllegalArgumentException("Unknown workflow execution: " + executionPublicId);
        }
    }

    public List<AiConclusion> list(String executionPublicId) {
        return jdbcTemplate.query("""
                SELECT c.public_id, e.public_id AS execution_public_id, c.claim_text, c.severity,
                       c.support_status, c.evidence_refs_json, c.sop_refs_json,
                       c.confidence_score, c.confidence_label, c.confidence_basis_json,
                       c.planner_json, c.recommended_action_json
                  FROM koc_ai_conclusion c
                  JOIN koc_workflow_execution e ON e.id = c.execution_id
                 WHERE e.public_id = ?
                 ORDER BY c.created_at ASC, c.id ASC
                """, new ConclusionRowMapper(), executionPublicId);
    }

    private final class ConclusionRowMapper implements RowMapper<AiConclusion> {

        @Override
        public AiConclusion mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AiConclusion(
                    rs.getString("public_id"),
                    rs.getString("execution_public_id"),
                    rs.getString("claim_text"),
                    rs.getString("severity"),
                    rs.getString("support_status"),
                    read(rs.getString("evidence_refs_json"), STRING_LIST, List.of()),
                    read(rs.getString("sop_refs_json"), SOP_LIST, List.of()),
                    new ConfidenceAssessment(
                            rs.getDouble("confidence_score"),
                            rs.getString("confidence_label"),
                            read(rs.getString("confidence_basis_json"), DOUBLE_MAP, Map.of())),
                    read(rs.getString("planner_json"), OBJECT_MAP, Map.of()),
                    read(
                            rs.getString("recommended_action_json"),
                            RecommendedAction.class,
                            new RecommendedAction("", false, Map.of())));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Conclusion JSON is invalid", ex);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private <T> T read(String value, Class<T> type, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private boolean executionExists(String executionPublicId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_workflow_execution WHERE public_id = ?", Integer.class, executionPublicId);
        return count != null && count > 0;
    }
}
