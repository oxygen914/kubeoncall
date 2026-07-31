package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.BuildRequest;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.EvidenceItem;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.EvidenceType;

class DiagnosticEvidenceBuilderTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");

    @Test
    void redactsSecretsTokensPrivateKeysAndSecretYamlData() {
        DiagnosticEvidenceBuilder builder = new DiagnosticEvidenceBuilder(new ObjectMapper());
        var evidence = builder.build(new BuildRequest(
                "req_1",
                NOW.minusSeconds(60),
                NOW,
                NOW,
                List.of(
                        new EvidenceItem(
                                EvidenceType.LOG, "pod/a", "Authorization: Bearer abcdefghijklmnop\npassword=hunter2"),
                        new EvidenceItem(
                                EvidenceType.RESOURCE_YAML,
                                "deployment/a",
                                "kind: Secret\ndata:\n  token: c2VjcmV0\nstringData: plain"),
                        new EvidenceItem(
                                EvidenceType.RESOURCE_DESCRIPTION,
                                "pod/a",
                                "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----"))));

        String content = new String(evidence.content(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
                .doesNotContain("hunter2", "abcdefghijklmnop", "c2VjcmV0", "BEGIN PRIVATE KEY")
                .contains("[REDACTED]", "[REDACTED_PRIVATE_KEY]");
    }

    @Test
    void boundsLogsAndOmitsBinaryContent() {
        DiagnosticEvidenceBuilder builder = new DiagnosticEvidenceBuilder(new ObjectMapper());
        String logs = String.join("\n", java.util.Collections.nCopies(600, "line"));
        var evidence = builder.build(new BuildRequest(
                "req_1",
                null,
                null,
                NOW,
                List.of(
                        new EvidenceItem(EvidenceType.LOG, "pod/a", logs),
                        new EvidenceItem(EvidenceType.LOG, "pod/b", "\u0000\u0001\u0002"))));

        assertThat(evidence.items().get(0).content()).contains("[TRUNCATED_LINES]");
        assertThat(evidence.items().get(1).content()).contains("[BINARY_OMITTED");
    }

    @Test
    void canonicalOrderProducesStableContentAndHash() {
        DiagnosticEvidenceBuilder builder = new DiagnosticEvidenceBuilder(new ObjectMapper());
        List<EvidenceItem> items = List.of(
                new EvidenceItem(EvidenceType.EVENT, "event/b", "reason=BackOff"),
                new EvidenceItem(EvidenceType.LOG, "pod/a", "line"));
        var first = builder.build(new BuildRequest("req_1", null, null, NOW, items));
        var second = builder.build(new BuildRequest("req_1", null, null, NOW, List.of(items.get(1), items.get(0))));

        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    @Test
    void rejectsOversizedOrInvalidTimeWindow() {
        DiagnosticEvidenceBuilder builder = new DiagnosticEvidenceBuilder(new ObjectMapper());
        assertThatThrownBy(() -> builder.build(new BuildRequest("req_1", NOW.minusSeconds(86401), NOW, NOW, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(new BuildRequest(
                        "req_1",
                        null,
                        null,
                        NOW,
                        java.util.Collections.nCopies(
                                DiagnosticEvidenceBuilder.MAX_ITEMS + 1,
                                new EvidenceItem(EvidenceType.LOG, "pod/a", "line")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
