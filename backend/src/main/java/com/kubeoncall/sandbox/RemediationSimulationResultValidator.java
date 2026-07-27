package com.kubeoncall.sandbox;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict boundary validator for the untrusted validation-cluster simulation result. */
final class RemediationSimulationResultValidator {

    private static final Set<String> REQUIRED = Set.of("schemaVersion", "outcome", "checks", "cleanupRequired");
    private static final Set<String> ALLOWED =
            Set.of("schemaVersion", "outcome", "checks", "cleanupRequired", "findings");

    private RemediationSimulationResultValidator() {}

    static boolean isValid(String value, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null
                    || !root.isObject()
                    || !root.path("cleanupRequired").asBoolean(false)) {
                return false;
            }
            for (String field : REQUIRED) {
                if (!root.has(field)) {
                    return false;
                }
            }
            java.util.Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                if (!ALLOWED.contains(fields.next())) {
                    return false;
                }
            }
            String outcome = root.path("outcome").asText();
            return "v1".equals(root.path("schemaVersion").asText())
                    && ("PASSED".equals(outcome) || "FAILED".equals(outcome) || "TIMED_OUT".equals(outcome))
                    && root.path("checks").isObject()
                    && (!root.has("findings") || root.path("findings").isArray());
        } catch (Exception ex) {
            return false;
        }
    }
}
