package com.kubeoncall.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    @Test
    void recursivelyMasksSensitiveKeysWithoutChangingBusinessFields() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", "FIRING");
        nested.put("apiKey", "sk-abcdefghijklmnopqrstuvwxyz");
        nested.put(
                "details",
                Map.of(
                        "password", "hunter2",
                        "resourceId", "node-01",
                        "notes", "Authorization: Bearer abcdefghijklmnopqrstuvwxyz"));

        Map<String, Object> redacted = SensitiveDataRedactor.STANDARD.redactMap(nested);

        assertThat(redacted).containsEntry("status", "FIRING");
        assertThat(redacted).containsEntry("apiKey", SensitiveDataRedactor.REDACTED);
        assertThat(redacted.get("details"))
                .isEqualTo(Map.of(
                        "password",
                        SensitiveDataRedactor.REDACTED,
                        "resourceId",
                        "node-01",
                        "notes",
                        "Authorization: Bearer [REDACTED]"));
        assertThat(nested.get("apiKey")).isEqualTo("sk-abcdefghijklmnopqrstuvwxyz");
    }

    @Test
    void masksJwtProviderKeysAndJsonStyleAssignmentsInText() {
        String text = """
                {"apiKey":"sk-abcdefghijklmnopqrstuvwxyz","password":"hunter2"}
                jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature12345678
                github=ghp_abcdefghijklmnopqrstuvwxyz123456
                """;

        String redacted = SensitiveDataRedactor.STANDARD.redactText(text);

        assertThat(redacted)
                .doesNotContain("hunter2")
                .doesNotContain("eyJhbGci")
                .doesNotContain("ghp_")
                .doesNotContain("sk-abcdefghijklmnopqrstuvwxyz")
                .contains("\"apiKey\":[REDACTED]")
                .contains("[REDACTED_JWT]");
    }

    @Test
    void boundsDepthCollectionSizeAndTextLength() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(2, 3, 32);
        List<Object> oversized = new ArrayList<>();
        oversized.add("one");
        oversized.add("two");
        oversized.add("three");
        oversized.add("four");
        Map<String, Object> value = Map.of(
                "nested", Map.of("tooDeep", Map.of("secret", "hidden")),
                "items", oversized,
                "text", "x".repeat(100));

        Map<String, Object> redacted = redactor.redactMap(value);

        assertThat(String.valueOf(redacted.get("nested"))).contains("[MAX_DEPTH]");
        assertThat(String.valueOf(redacted.get("items"))).contains("[TRUNCATED]");
        assertThat(String.valueOf(redacted.get("text")).length()).isLessThanOrEqualTo(45);
    }
}
