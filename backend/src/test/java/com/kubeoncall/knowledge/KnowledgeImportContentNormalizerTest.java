package com.kubeoncall.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class KnowledgeImportContentNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KnowledgeImportContentNormalizer normalizer = new KnowledgeImportContentNormalizer(objectMapper);

    @Test
    void normalizesMarkdownDocumentIntoCanonicalJsonl() throws Exception {
        KnowledgeImportContentNormalizer.NormalizedImport result = normalizer.normalize(
                "DOCUMENT",
                "node-runbook.md",
                "text/markdown",
                "# Node recovery\nRestart safely.".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> document = objectMapper.readValue(result.jsonlContent(), new TypeReference<>() {});
        assertThat(result.importType()).isEqualTo("DOCUMENT");
        assertThat(document).containsEntry("title", "node-runbook");
        assertThat(String.valueOf(document.get("content"))).contains("Node recovery");
        assertThat(document).containsEntry("document_type", "DOCUMENT");
    }

    @Test
    void marksRunbookDocumentsForRetrievalMetadata() throws Exception {
        KnowledgeImportContentNormalizer.NormalizedImport result = normalizer.normalize(
                "RUNBOOK", "restart.txt", "text/plain", "restart the service".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> document = objectMapper.readValue(result.jsonlContent(), new TypeReference<>() {});
        assertThat(result.importType()).isEqualTo("RUNBOOK");
        assertThat(document).containsEntry("document_type", "RUNBOOK");
        assertThat(document).containsEntry("source_type", "RUNBOOK");
    }

    @Test
    void preservesJsonlPayloadWithoutParsingIt() {
        byte[] payload = "{\"title\":\"Node\",\"content\":\"guide\"}\n".getBytes(StandardCharsets.UTF_8);

        KnowledgeImportContentNormalizer.NormalizedImport result =
                normalizer.normalize("JSONL", "knowledge.jsonl", "application/x-ndjson", payload);

        assertThat(result.importType()).isEqualTo("JSONL");
        assertThat(result.jsonlContent()).isEqualTo(payload);
    }

    @Test
    void rejectsUnsupportedDocumentExtensionBeforeCreatingTask() {
        assertThatThrownBy(
                        () -> normalizer.normalize("DOCUMENT", "archive.zip", "application/zip", new byte[] {1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document format");
    }
}
