package com.kubeoncall.sandbox.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

class YamlSandboxToolCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsThreeDigestPinnedFixedDiagnosticTools() {
        YamlSandboxToolCatalog catalog = new YamlSandboxToolCatalog();

        for (String id : java.util.List.of("log-pattern-analysis", "kubernetes-consistency", "configuration-diff")) {
            SandboxToolSpec spec =
                    catalog.find(id, "v1", SandboxRunMode.FIXED_DIAGNOSTIC).orElseThrow();
            assertThat(SandboxToolSpec.isDigestPinned(spec.imageDigest())).isTrue();
            assertThat(spec.imageDigest()).matches(".+@sha256:[0-9a-f]{64}");
            assertThat(spec.entrypoint()).isEqualTo("/tool");
            assertThat(spec.networkEgressPolicy())
                    .isEqualTo(SandboxToolSpec.NetworkEgressPolicy.DNS_AND_ARTIFACT_CHANNEL);
            assertThat(spec.resourceLimits().timeoutSeconds())
                    .isLessThanOrEqualTo(spec.resourceLimits().maxTimeoutSeconds());
            assertThat(spec.inputSchemaRef()).isEqualTo("sandbox-tools/schemas/evidence-v1.json");
            assertThat(spec.outputSchemaRef()).isEqualTo("sandbox-tools/schemas/diagnosis-v1.json");
        }
        assertThat(catalog.find("log-pattern-analysis", "v1", SandboxRunMode.GENERATED_CODE))
                .isEmpty();
    }

    @Test
    void rejectsDuplicateIdentifiersAndMissingSchemaContracts() {
        String duplicated = """
                tools:
                  - {id: log-pattern-analysis, version: v1, mode: FIXED_DIAGNOSTIC, imageDigest: repo/a@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa, entrypoint: /tool, networkEgressPolicy: DENY_ALL, inputSchemaRef: input.json, outputSchemaRef: output.json, resources: {cpu: 1m, memory: 1Mi, ephemeralStorage: 1Mi, inputMaxBytes: 1, outputMaxBytes: 1, logMaxBytes: 1, scriptMaxBytes: 1, timeoutSeconds: 1, maxTimeoutSeconds: 1}}
                  - {id: log-pattern-analysis, version: v1, mode: FIXED_DIAGNOSTIC, imageDigest: repo/b@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb, entrypoint: /tool, networkEgressPolicy: DENY_ALL, inputSchemaRef: input.json, outputSchemaRef: output.json, resources: {cpu: 1m, memory: 1Mi, ephemeralStorage: 1Mi, inputMaxBytes: 1, outputMaxBytes: 1, logMaxBytes: 1, scriptMaxBytes: 1, timeoutSeconds: 1, maxTimeoutSeconds: 1}}
                  - {id: configuration-diff, version: v1, mode: FIXED_DIAGNOSTIC, imageDigest: repo/c@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc, entrypoint: /tool, networkEgressPolicy: DENY_ALL, inputSchemaRef: input.json, outputSchemaRef: output.json, resources: {cpu: 1m, memory: 1Mi, ephemeralStorage: 1Mi, inputMaxBytes: 1, outputMaxBytes: 1, logMaxBytes: 1, scriptMaxBytes: 1, timeoutSeconds: 1, maxTimeoutSeconds: 1}}
                """;

        assertThatThrownBy(() -> new YamlSandboxToolCatalog(asStream(duplicated), ignored -> true))
                .hasMessageContaining("duplicate sandbox tool");
        assertThatThrownBy(() -> new YamlSandboxToolCatalog(
                        asStream(duplicated.replace("input.json", "missing.json")),
                        path -> !path.equals("missing.json")))
                .hasMessageContaining("schema reference is missing");
        assertThatThrownBy(() -> new YamlSandboxToolCatalog(
                        asStream(duplicated
                                .replace("FIXED_DIAGNOSTIC", "GENERATED_CODE")
                                .replace("DENY_ALL", "DNS_AND_ARTIFACT_CHANNEL")),
                        ignored -> true))
                .hasMessageContaining("must default to DENY_ALL");
    }

    @Test
    void schemaAndReplaySamplesKeepTheDiagnosisContractCommandFree() throws Exception {
        JsonNode evidenceSchema = readJson("sandbox-tools/schemas/evidence-v1.json");
        JsonNode diagnosisSchema = readJson("sandbox-tools/schemas/diagnosis-v1.json");
        assertThat(evidenceSchema.path("required"))
                .extracting(JsonNode::asText)
                .contains("schemaVersion", "requestId", "classification", "items");
        assertThat(diagnosisSchema.path("required"))
                .extracting(JsonNode::asText)
                .contains("schemaVersion", "findings", "evidenceReferences", "confidence", "recommendations");
        assertThat(diagnosisSchema.path("additionalProperties").asBoolean()).isFalse();

        for (String sample : List.of(
                "log-pattern-analysis.diagnosis.json",
                "kubernetes-consistency.diagnosis.json",
                "configuration-diff.diagnosis.json")) {
            JsonNode result = readJson("sandbox-tools/samples/" + sample);
            assertThat(result.path("schemaVersion").asText()).isEqualTo("v1");
            assertThat(result.path("findings").isArray()).isTrue();
            assertThat(result.path("evidenceReferences").isArray()).isTrue();
            assertThat(result.path("recommendations").isArray()).isTrue();
            assertThat(result.path("confidence").asDouble()).isBetween(0d, 1d);
            assertThat(result.has("command")).isFalse();
            assertThat(result.has("commands")).isFalse();
            assertThat(result.has("shell")).isFalse();
            assertThat(result.has("exec")).isFalse();
        }
    }

    @Test
    void exposesOnlyPinnedGeneratedCodeRuntimesWithDenyAllNetwork() {
        YamlSandboxToolCatalog catalog = new YamlSandboxToolCatalog();

        for (String id : List.of("generated-python", "generated-posix-shell")) {
            SandboxToolSpec runtime =
                    catalog.find(id, "v1", SandboxRunMode.GENERATED_CODE).orElseThrow();
            assertThat(runtime.imageDigest()).matches(".+@sha256:[0-9a-f]{64}");
            assertThat(runtime.networkEgressPolicy()).isEqualTo(SandboxToolSpec.NetworkEgressPolicy.DENY_ALL);
            assertThat(runtime.inputSchemaRef()).isEqualTo("sandbox-tools/schemas/generated-code-input-v1.json");
            assertThat(runtime.outputSchemaRef()).isEqualTo("sandbox-tools/schemas/generated-code-result-v1.json");
        }
    }

    private JsonNode readJson(String path) throws Exception {
        return objectMapper.readTree(new ClassPathResource(path).getInputStream());
    }

    private static ByteArrayInputStream asStream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
