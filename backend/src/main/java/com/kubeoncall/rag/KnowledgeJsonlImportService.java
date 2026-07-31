package com.kubeoncall.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;

/** Imports one generic knowledge document per non-blank JSONL line. */
@Service
public class KnowledgeJsonlImportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final KnowledgeIngestService knowledgeIngestService;
    private final KubeOnCallProperties properties;

    public KnowledgeJsonlImportService(
            ObjectMapper objectMapper, KnowledgeIngestService knowledgeIngestService, KubeOnCallProperties properties) {
        this.objectMapper = objectMapper;
        this.knowledgeIngestService = knowledgeIngestService;
        this.properties = properties;
    }

    public ImportResult importJsonl(String payload) {
        validatePayload(payload);
        List<LineResult> lines = new ArrayList<>();
        int scanned = 0;
        int imported = 0;
        String[] rawLines = payload == null ? new String[0] : payload.split("\\R");
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = rawLines[index].trim();
            if (rawLine.isBlank()) {
                continue;
            }
            scanned++;
            try {
                Map<String, Object> item = objectMapper.readValue(rawLine, MAP_TYPE);
                String title = requiredText(item, "title");
                String content = requiredText(item, "content");
                validateContent(content);
                String source = text(item.get("source"));
                KnowledgeDocument document = knowledgeIngestService.ingest(
                        title, content, source.isBlank() ? "jsonl" : source, metadata(item));
                imported++;
                lines.add(new LineResult(index + 1, "imported", document.id(), "success"));
            } catch (Exception ex) {
                lines.add(new LineResult(index + 1, "failed", null, "Invalid JSONL document"));
            }
        }
        return new ImportResult(scanned, imported, scanned - imported, List.copyOf(lines));
    }

    private void validatePayload(String payload) {
        if (payload == null) {
            return;
        }
        int maxPayloadBytes = Math.max(1, properties.getRag().getJsonlMaxPayloadBytes());
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new IllegalArgumentException("JSONL payload exceeds the configured byte limit");
        }
        int maxLines = Math.max(1, properties.getRag().getJsonlMaxLines());
        long lineCount = payload.lines().limit((long) maxLines + 1).count();
        if (lineCount > maxLines) {
            throw new IllegalArgumentException("JSONL payload exceeds the configured line limit");
        }
    }

    private void validateContent(String content) {
        int maxContentBytes = Math.max(1, properties.getRag().getDocumentMaxContentBytes());
        if (content.getBytes(StandardCharsets.UTF_8).length > maxContentBytes) {
            throw new IllegalArgumentException("Knowledge document content exceeds the configured byte limit");
        }
    }

    private Map<String, String> metadata(Map<String, Object> item) {
        Map<String, String> metadata = new LinkedHashMap<>();
        Object rawMetadata = item.get("metadata");
        if (rawMetadata instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String normalizedKey = text(key);
                String normalizedValue = text(value);
                if (!normalizedKey.isBlank() && !normalizedValue.isBlank()) {
                    metadata.put(normalizedKey, normalizedValue);
                }
            });
        }
        copyTopLevel(item, metadata, "doc_id");
        if (!metadata.containsKey("doc_id")) {
            copyTopLevel(item, metadata, "id", "doc_id");
        }
        copyTopLevel(item, metadata, "dataset_version");
        copyTopLevel(item, metadata, "document_type");
        copyTopLevel(item, metadata, "source_type");
        return metadata;
    }

    private void copyTopLevel(Map<String, Object> item, Map<String, String> metadata, String key) {
        copyTopLevel(item, metadata, key, key);
    }

    private void copyTopLevel(
            Map<String, Object> item, Map<String, String> metadata, String sourceKey, String targetKey) {
        String value = text(item.get(sourceKey));
        if (!value.isBlank()) {
            metadata.put(targetKey, value);
        }
    }

    private String requiredText(Map<String, Object> item, String key) {
        String value = text(item.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record ImportResult(int scanned, int imported, int failed, List<LineResult> lines) {}

    public record LineResult(int line, String status, String documentId, String message) {}
}
