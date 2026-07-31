package com.kubeoncall.sandbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

final class SandboxRunRowMapper implements RowMapper<SandboxRunRecord> {

    static final String COLUMNS = """
            r.id, r.public_id, r.execution_public_id, r.alarm_public_id, r.mode, r.tool_id,
            r.tool_version, r.runtime_image_digest, r.run_status, r.cleanup_status, r.stage,
            r.progress, r.risk_level, r.requested_by, r.idempotency_key, r.request_json,
            r.result_json, r.error_code, r.error_summary, r.controller_run_id, r.owner_token,
            r.lease_until, r.fencing_token, r.attempt, r.max_attempts, r.expires_at, r.request_id,
            r.trace_id, r.version, r.started_at, r.finished_at, r.created_at, r.updated_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    SandboxRunRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SandboxRunRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SandboxRunRecord(
                rs.getLong("id"),
                rs.getString("public_id"),
                rs.getString("execution_public_id"),
                rs.getString("alarm_public_id"),
                SandboxRunMode.valueOf(rs.getString("mode")),
                rs.getString("tool_id"),
                rs.getString("tool_version"),
                rs.getString("runtime_image_digest"),
                SandboxRunStatus.valueOf(rs.getString("run_status")),
                SandboxCleanupStatus.valueOf(rs.getString("cleanup_status")),
                rs.getString("stage"),
                rs.getInt("progress"),
                SandboxRiskLevel.valueOf(rs.getString("risk_level")),
                rs.getString("requested_by"),
                rs.getString("idempotency_key"),
                parseMap(rs.getString("request_json")),
                parseMap(rs.getString("result_json")),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                rs.getString("controller_run_id"),
                rs.getString("owner_token"),
                instant(rs, "lease_until"),
                rs.getLong("fencing_token"),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                instant(rs, "expires_at"),
                rs.getString("request_id"),
                rs.getString("trace_id"),
                rs.getLong("version"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private Map<String, Object> parseMap(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid sandbox run JSON", ex);
        }
    }
}
