package com.kubeoncall.rag.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.reindex.ReindexRequest;
import org.springframework.data.elasticsearch.core.reindex.ReindexResponse;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class KnowledgeIndexAdmin {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KubeOnCallProperties properties;
    private volatile boolean vectorMappingReady;

    public KnowledgeIndexAdmin(ElasticsearchTemplate elasticsearchTemplate, KubeOnCallProperties properties) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.properties = properties;
    }

    public void ensureVectorMapping(int actualDimensions) {
        int configuredDimensions = configuredDimensions();
        if (actualDimensions != configuredDimensions) {
            throw new IllegalArgumentException("Embedding dimensions " + actualDimensions
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
            if (aliasEnabled() && !operations.exists()) {
                String initialIndex = versionedIndex("v1");
                IndexOperations initialOperations = elasticsearchTemplate.indexOps(IndexCoordinates.of(initialIndex));
                if (!initialOperations.exists()
                        && !initialOperations.create(Map.of(), fullMapping(configuredDimensions))) {
                    throw new IllegalStateException("Failed to create initial knowledge version index");
                }
                if (!aliasAction(initialIndex, true, false)) {
                    throw new IllegalStateException("Failed to activate initial knowledge index alias");
                }
                operations = elasticsearchTemplate.indexOps(index());
            }
            if (!operations.exists()) {
                if (!operations.create(Map.of(), fullMapping(configuredDimensions))) {
                    throw new IllegalStateException("Failed to create knowledge index vector mapping");
                }
            } else {
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
            vectorMappingReady = true;
        }
    }

    public boolean indexExists() {
        return elasticsearchTemplate.indexOps(index()).exists();
    }

    public boolean aliasEnabled() {
        return properties.getRag().getKnowledgeIndexAlias() != null
                && !properties.getRag().getKnowledgeIndexAlias().isBlank();
    }

    public VersionPreparation prepareVersion(String rawVersion, boolean reindex) {
        requireAlias();
        String version = normalizeVersion(rawVersion);
        String target = versionedIndex(version);
        IndexOperations targetOperations = elasticsearchTemplate.indexOps(IndexCoordinates.of(target));
        boolean created = !targetOperations.exists();
        if (created && !targetOperations.create(Map.of(), fullMapping(configuredDimensions()))) {
            throw new IllegalStateException("Failed to create knowledge version index " + target);
        }
        ReindexResponse response = null;
        if (reindex) {
            if (!indexExists()) {
                throw new IllegalStateException("Knowledge source index/alias does not exist");
            }
            response = elasticsearchTemplate.reindex(ReindexRequest.builder(index(), IndexCoordinates.of(target))
                    .withRefresh(true)
                    .build());
        }
        return new VersionPreparation(
                version,
                target,
                created,
                response == null ? 0 : response.getTotal(),
                response == null ? 0 : response.getCreated(),
                response == null ? 0 : response.getUpdated());
    }

    public AliasStatus aliasStatus() {
        if (!aliasEnabled()) {
            return new AliasStatus("", "", List.of());
        }
        if (!elasticsearchTemplate.indexOps(index()).exists()) {
            return new AliasStatus(aliasName(), "", List.of());
        }
        Map<String, java.util.Set<org.springframework.data.elasticsearch.core.index.AliasData>> aliases =
                elasticsearchTemplate
                        .indexOps(index())
                        .getAliases(properties.getRag().getKnowledgeIndexAlias());
        List<String> backing =
                aliases == null ? List.of() : aliases.keySet().stream().sorted().toList();
        String active = backing.size() == 1 ? backing.get(0) : "";
        return new AliasStatus(properties.getRag().getKnowledgeIndexAlias(), active, backing);
    }

    public AliasStatus activateVersion(String rawVersion) {
        requireAlias();
        String version = normalizeVersion(rawVersion);
        String target = versionedIndex(version);
        if (!elasticsearchTemplate.indexOps(IndexCoordinates.of(target)).exists()) {
            throw new IllegalArgumentException("Knowledge version does not exist: " + version);
        }
        AliasStatus current = aliasStatus();
        if (current.backingIndices().isEmpty()
                && elasticsearchTemplate.indexOps(index()).exists()) {
            throw new IllegalStateException(
                    "Configured knowledge-index-alias points to a concrete legacy index; migrate it before activation");
        }
        List<AliasAction> actions = new ArrayList<>();
        for (String backing : current.backingIndices()) {
            actions.add(new AliasAction.Remove(AliasActionParameters.builder()
                    .withIndices(backing)
                    .withAliases(aliasName())
                    .build()));
        }
        actions.add(new AliasAction.Add(AliasActionParameters.builder()
                .withIndices(target)
                .withAliases(aliasName())
                .withIsWriteIndex(true)
                .build()));
        if (!elasticsearchTemplate
                .indexOps(IndexCoordinates.of(target))
                .alias(new AliasActions(actions.toArray(AliasAction[]::new)))) {
            throw new IllegalStateException("Failed to activate knowledge alias " + aliasName());
        }
        return aliasStatus();
    }

    public AliasStatus rollback(String rawVersion) {
        return activateVersion(rawVersion);
    }

    String normalizeVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        String version = rawVersion.trim().toLowerCase(java.util.Locale.ROOT);
        if (!version.matches("v?[a-z0-9][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("version must contain only letters, digits, dot, underscore or hyphen");
        }
        return version.startsWith("v") ? version : "v" + version;
    }

    String versionedIndex(String version) {
        return properties.getRag().getKnowledgeIndex() + "-" + normalizeVersion(version);
    }

    private boolean aliasAction(String target, boolean writeIndex, boolean removeExisting) {
        List<AliasAction> actions = new ArrayList<>();
        if (removeExisting) {
            for (String backing : aliasStatus().backingIndices()) {
                actions.add(new AliasAction.Remove(AliasActionParameters.builder()
                        .withIndices(backing)
                        .withAliases(aliasName())
                        .build()));
            }
        }
        actions.add(new AliasAction.Add(AliasActionParameters.builder()
                .withIndices(target)
                .withAliases(aliasName())
                .withIsWriteIndex(writeIndex)
                .build()));
        return elasticsearchTemplate
                .indexOps(IndexCoordinates.of(target))
                .alias(new AliasActions(actions.toArray(AliasAction[]::new)));
    }

    private void requireAlias() {
        if (!aliasEnabled()) {
            throw new IllegalStateException("knowledge-index-alias is not configured");
        }
    }

    private String aliasName() {
        requireAlias();
        return properties.getRag().getKnowledgeIndexAlias().trim();
    }

    public record VersionPreparation(
            String version, String index, boolean created, long total, long createdDocuments, long updatedDocuments) {}

    public record AliasStatus(String alias, String activeIndex, List<String> backingIndices) {}

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

    private int configuredDimensions() {
        return Math.max(1, properties.getRag().getEmbeddingDimensions());
    }

    private IndexCoordinates index() {
        return IndexCoordinates.of(
                aliasEnabled()
                        ? properties.getRag().getKnowledgeIndexAlias().trim()
                        : properties.getRag().getKnowledgeIndex());
    }
}
