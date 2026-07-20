package com.kubeoncall.rag.repository;

import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

/** Initializes the active knowledge index and validates its vector mapping exactly once per process. */
@Component
public class KnowledgeIndexInitializer {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KnowledgeIndexNaming indexNaming;
    private final KnowledgeIndexMappingManager mappingManager;
    private final KnowledgeIndexAliasManager aliasManager;
    private volatile boolean vectorMappingReady;

    public KnowledgeIndexInitializer(
            ElasticsearchTemplate elasticsearchTemplate,
            KnowledgeIndexNaming indexNaming,
            KnowledgeIndexMappingManager mappingManager,
            KnowledgeIndexAliasManager aliasManager) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.indexNaming = indexNaming;
        this.mappingManager = mappingManager;
        this.aliasManager = aliasManager;
    }

    public void ensureVectorMapping(int actualDimensions) {
        int configuredDimensions = indexNaming.configuredDimensions();
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
            IndexOperations operations = elasticsearchTemplate.indexOps(indexNaming.activeIndex());
            if (indexNaming.aliasEnabled() && !operations.exists()) {
                initializeVersionedAlias(configuredDimensions);
                operations = elasticsearchTemplate.indexOps(indexNaming.activeIndex());
            }
            mappingManager.ensureMapping(operations, configuredDimensions);
            vectorMappingReady = true;
        }
    }

    private void initializeVersionedAlias(int dimensions) {
        String initialIndex = indexNaming.versionedIndex("v1");
        IndexOperations initialOperations = elasticsearchTemplate.indexOps(IndexCoordinates.of(initialIndex));
        if (!initialOperations.exists() && !mappingManager.createMapping(initialOperations, dimensions)) {
            throw new IllegalStateException("Failed to create initial knowledge version index");
        }
        if (!aliasManager.activateInitial(initialIndex)) {
            throw new IllegalStateException("Failed to activate initial knowledge index alias");
        }
    }
}
