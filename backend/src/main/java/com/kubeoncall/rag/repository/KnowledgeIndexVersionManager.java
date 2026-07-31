package com.kubeoncall.rag.repository;

import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.reindex.ReindexRequest;
import org.springframework.data.elasticsearch.core.reindex.ReindexResponse;
import org.springframework.stereotype.Component;

/** Prepares versioned knowledge indices and optionally reindexes the active index into them. */
@Component
public class KnowledgeIndexVersionManager {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KnowledgeIndexNaming indexNaming;
    private final KnowledgeIndexMappingManager mappingManager;

    public KnowledgeIndexVersionManager(
            ElasticsearchTemplate elasticsearchTemplate,
            KnowledgeIndexNaming indexNaming,
            KnowledgeIndexMappingManager mappingManager) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.indexNaming = indexNaming;
        this.mappingManager = mappingManager;
    }

    public boolean indexExists() {
        return elasticsearchTemplate.indexOps(indexNaming.activeIndex()).exists();
    }

    public PreparedVersion prepare(String rawVersion, boolean reindex) {
        indexNaming.requireAlias();
        String version = indexNaming.normalizeVersion(rawVersion);
        String target = indexNaming.versionedIndex(version);
        IndexOperations targetOperations = elasticsearchTemplate.indexOps(IndexCoordinates.of(target));
        boolean created = !targetOperations.exists();
        if (created && !mappingManager.createMapping(targetOperations, indexNaming.configuredDimensions())) {
            throw new IllegalStateException("Failed to create knowledge version index " + target);
        }
        ReindexResponse response = null;
        if (reindex) {
            if (!indexExists()) {
                throw new IllegalStateException("Knowledge source index/alias does not exist");
            }
            response = elasticsearchTemplate.reindex(
                    ReindexRequest.builder(indexNaming.activeIndex(), IndexCoordinates.of(target))
                            .withRefresh(true)
                            .build());
        }
        return new PreparedVersion(
                version,
                target,
                created,
                response == null ? 0 : response.getTotal(),
                response == null ? 0 : response.getCreated(),
                response == null ? 0 : response.getUpdated());
    }

    public record PreparedVersion(
            String version, String index, boolean created, long total, long createdDocuments, long updatedDocuments) {}
}
