package com.kubeoncall.sandbox;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict boundary validator for untrusted fixed manifest-validator output. */
final class ManifestValidationResultValidator {

    private static final Set<String> REQUIRED = Set.of("schemaVersion", "valid", "findings", "toolVersions");
    private static final Set<String> ALLOWED =
            Set.of("schemaVersion", "valid", "findings", "renderedOrPatched", "toolVersions");

    private ManifestValidationResultValidator() {}

    static boolean isValid(String value, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isObject()) {
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
            return "v1".equals(root.path("schemaVersion").asText())
                    && root.path("valid").isBoolean()
                    && root.path("findings").isArray()
                    && root.path("toolVersions").isObject();
        } catch (Exception ex) {
            return false;
        }
    }
}
