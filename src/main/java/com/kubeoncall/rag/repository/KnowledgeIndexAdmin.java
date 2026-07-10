package com.kubeoncall.rag.repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class KnowledgeIndexAdmin {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KubeOnCallProperties properties;
    private volatile boolean vectorMappingReady;

    public KnowledgeIndexAdmin(ElasticsearchTemplate elasticsearchTemplate,
                               KubeOnCallProperties properties) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.properties = properties;
    }

    public void ensureVectorMapping(int actualDimensions) {
        int configuredDimensions = configuredDimensions();
        if (actualDimensions != configuredDimensions) {
            throw new IllegalArgumentException(
                    "Embedding dimensions " + actualDimensions
                            + " do not match configured dimensions " + configuredDimensions);
        }
        if (vectorMappingReady) {
            return;
        }
        synchronized (this) {
            if (vectorMappingReady) {
                return;
            }
            IndexOperations operations = elasticsearchTemplate.indexOps(index());
            if (!operations.exists()) {
                if (!operations.create(Map.of(), fullMapping(configuredDimensions))) {
                    throw new IllegalStateException("Failed to create knowledge index vector mapping");
                }
            } else {
                Map<String, Object> currentMapping = operations.getMapping();
                Integer existingDimensions = vectorDimensions(currentMapping);
                String existingMetadataType = fieldType(currentMapping, "metadata");
                if (existingMetadataType != null && !"flattened".equals(existingMetadataType)) {
                    throw new IllegalStateException(
                            "Knowledge index metadata mapping is " + existingMetadataType
                                    + "; use a new index and reimport knowledge with flattened metadata");
                }
                if (existingDimensions != null && existingDimensions != configuredDimensions) {
                    throw new IllegalStateException(
                            "Knowledge index embedding dimensions " + existingDimensions
                                    + " do not match configured dimensions " + configuredDimensions);
                }
                if (existingDimensions == null || existingMetadataType == null) {
                    if (!operations.putMapping(missingFieldsMapping(
                            configuredDimensions,
                            existingDimensions == null,
                            existingMetadataType == null))) {
                        throw new IllegalStateException("Failed to add knowledge index mapping fields");
                    }
                }
            }
            vectorMappingReady = true;
        }
    }

    public boolean indexExists() {
        return elasticsearchTemplate.indexOps(index()).exists();
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
        return Map.of(
                "type", "dense_vector",
                "dims", dimensions,
                "index", true,
                "similarity", "cosine"
        );
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

    private int configuredDimensions() {
        return Math.max(1, properties.getRag().getEmbeddingDimensions());
    }

    private IndexCoordinates index() {
        return IndexCoordinates.of(properties.getRag().getKnowledgeIndex());
    }
}
