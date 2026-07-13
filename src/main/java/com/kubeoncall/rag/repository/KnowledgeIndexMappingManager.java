package com.kubeoncall.rag.repository;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.stereotype.Component;

/** Creates, validates, and extends the Elasticsearch mapping required by knowledge retrieval. */
@Component
public class KnowledgeIndexMappingManager {

    public void ensureMapping(IndexOperations operations, int configuredDimensions) {
        if (!operations.exists()) {
            if (!createMapping(operations, configuredDimensions)) {
                throw new IllegalStateException("Failed to create knowledge index vector mapping");
            }
            return;
        }
        Map<String, Object> currentMapping = operations.getMapping();
        Integer existingDimensions = vectorDimensions(currentMapping);
        String existingMetadataType = fieldType(currentMapping, "metadata");
        if (existingMetadataType != null && !"flattened".equals(existingMetadataType)) {
            throw new IllegalStateException("Knowledge index metadata mapping is " + existingMetadataType
                    + "; use a new index and reimport knowledge with flattened metadata");
        }
        if (existingDimensions != null && existingDimensions != configuredDimensions) {
            throw new IllegalStateException("Knowledge index embedding dimensions " + existingDimensions
                    + " do not match configured dimensions " + configuredDimensions);
        }
        if (existingDimensions == null || existingMetadataType == null) {
            if (!operations.putMapping(missingFieldsMapping(
                    configuredDimensions, existingDimensions == null, existingMetadataType == null))) {
                throw new IllegalStateException("Failed to add knowledge index mapping fields");
            }
        }
    }

    public boolean createMapping(IndexOperations operations, int dimensions) {
        return operations.create(Map.of(), fullMapping(dimensions));
    }

    Document fullMapping(int dimensions) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", Map.of("type", "text"));
        fields.put("content", Map.of("type", "text"));
        fields.put("source", Map.of("type", "keyword"));
        fields.put("metadata", Map.of("type", "flattened"));
        fields.put("createdAtEpochMs", Map.of("type", "long"));
        fields.put("embedding_text", Map.of("type", "text", "index", false));
        fields.put("embedding", vectorField(dimensions));
        return Document.from(Map.of("properties", fields));
    }

    Document missingFieldsMapping(int dimensions, boolean includeVector, boolean includeMetadata) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (includeVector) {
            fields.put("embedding", vectorField(dimensions));
        }
        if (includeMetadata) {
            fields.put("metadata", Map.of("type", "flattened"));
        }
        return Document.from(Map.of("properties", fields));
    }

    private Map<String, Object> vectorField(int dimensions) {
        return Map.of("type", "dense_vector", "dims", dimensions, "index", true, "similarity", "cosine");
    }

    private Integer vectorDimensions(Map<String, Object> mapping) {
        Object propertiesNode = mapping == null ? null : mapping.get("properties");
        if (!(propertiesNode instanceof Map<?, ?> fields)) {
            return null;
        }
        Object embeddingNode = fields.get("embedding");
        if (!(embeddingNode instanceof Map<?, ?> embeddingField)) {
            return null;
        }
        Object dimensions = embeddingField.get("dims");
        if (dimensions instanceof Number number) {
            return number.intValue();
        }
        if (dimensions != null) {
            try {
                return Integer.parseInt(String.valueOf(dimensions));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String fieldType(Map<String, Object> mapping, String fieldName) {
        Object propertiesNode = mapping == null ? null : mapping.get("properties");
        if (!(propertiesNode instanceof Map<?, ?> fields)) {
            return null;
        }
        Object fieldNode = fields.get(fieldName);
        if (!(fieldNode instanceof Map<?, ?> field)) {
            return null;
        }
        Object type = field.get("type");
        return type == null ? null : String.valueOf(type);
    }
}
