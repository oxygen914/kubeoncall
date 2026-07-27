package com.kubeoncall.sandbox;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates the small generated-code result contract before it becomes a persisted Artifact. */
final class GeneratedCodeResultValidator {

    private static final Set<String> FIELDS =
            Set.of("schemaVersion", "status", "findings", "evidenceReferences", "summary");
    private static final Set<String> STATUSES = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED");

    private GeneratedCodeResultValidator() {}

    static boolean isValid(String value, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject() || root.size() != FIELDS.size()) {
                return false;
            }
            java.util.Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!FIELDS.contains(fields.next())) {
                    return false;
                }
            }
            return "v1".equals(root.path("schemaVersion").asText())
                    && STATUSES.contains(root.path("status").asText())
                    && root.path("findings").isArray()
                    && root.path("evidenceReferences").isArray()
                    && root.path("summary").isTextual()
                    && root.path("summary").textValue().length() <= 16_384;
        } catch (Exception ex) {
            return false;
        }
    }
}
