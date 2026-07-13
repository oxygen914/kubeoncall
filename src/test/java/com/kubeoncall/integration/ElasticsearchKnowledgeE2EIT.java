package com.kubeoncall.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.rag.runbook.RunbookImportService;

@SpringBootTest(
        properties = {
            "spring.elasticsearch.uris=http://localhost:9200",
            "kubeoncall.rag.knowledge-index=kubeoncall-knowledge-it",
            "kubeoncall.rag.vector-enabled=true",
            "kubeoncall.rag.vector-backend=es",
            "kubeoncall.rag.es-knn-enabled=true",
            "kubeoncall.rag.embedding-enabled=false",
            "kubeoncall.rag.mock-embedding-enabled=true",
            "kubeoncall.rag.embedding-dimensions=32",
            "kubeoncall.rag.chunk-size=400",
            "kubeoncall.rag.chunk-overlap=50",
            "kubeoncall.rag.runbook-bootstrap-enabled=false",
            "kubeoncall.storage.minio.bucket=",
            "kubeoncall.mcp.enabled=false",
            "kubeoncall.agent.planner-llm-enabled=false",
            "kubeoncall.memory.consolidation-enabled=false"
        })
class ElasticsearchKnowledgeE2EIT {

    private static final IndexCoordinates INDEX = IndexCoordinates.of("kubeoncall-knowledge-it");

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Autowired
    private RunbookImportService runbookImportService;

    @Autowired
    private KnowledgeIngestService knowledgeIngestService;

    @BeforeEach
    void resetIndex() {
        deleteIndex();
    }

    @AfterEach
    void cleanupIndex() {
        deleteIndex();
    }

    @Test
    void shouldImportAndRetrieveRunbooksThroughRealElasticsearch() {
        RunbookImportService.ImportResult imported = runbookImportService.importAll(false);
        assertEquals(8, imported.imported(), () -> "runbook import results: " + imported.assets());
        assertEquals(0, imported.failed());

        IndexOperations operations = elasticsearchTemplate.indexOps(INDEX);
        operations.refresh();
        Map<String, Object> mapping = operations.getMapping();
        Map<?, ?> properties = (Map<?, ?>) mapping.get("properties");
        assertEquals("flattened", ((Map<?, ?>) properties.get("metadata")).get("type"));
        assertEquals("dense_vector", ((Map<?, ?>) properties.get("embedding")).get("type"));
        assertEquals(32, ((Number) ((Map<?, ?>) properties.get("embedding")).get("dims")).intValue());

        Map<String, String> filters = Map.of(
                "runbookId", "runbook-pod-oom",
                "document_type", "runbook",
                "category", "k8s-pod");
        RetrievalResult keyword =
                knowledgeIngestService.retrieve("OOMKilled 内存 limit 怎么处理", filters, 3, RetrieveMethod.KEYWORD, true);
        assertFalse(keyword.documents().isEmpty());
        assertEquals("runbook-pod-oom", keyword.documents().get(0).metadata().get("runbookId"));
        assertTrue(keyword.documents().get(0).content().contains("安全边界"));
        assertEquals(true, keyword.diagnostics().get("parentAggregationApplied"));

        RetrievalResult vector = knowledgeIngestService.retrieve("容器内存溢出恢复", filters, 3, RetrieveMethod.VECTOR, true);
        assertFalse(vector.documents().isEmpty());
        assertEquals("runbook-pod-oom", vector.documents().get(0).metadata().get("runbookId"));
        assertEquals("elasticsearch_knn", vector.diagnostics().get("vectorSource"));
        assertEquals(false, vector.diagnostics().get("vectorFallback"));
        assertEquals(true, vector.diagnostics().get("parentAggregationApplied"));

        operations.refresh();
        RunbookImportService.ImportResult repeated = runbookImportService.importAll(false);
        assertEquals(0, repeated.imported());
        assertEquals(8, repeated.skipped());
        assertEquals(0, repeated.failed());
    }

    private void deleteIndex() {
        IndexOperations operations = elasticsearchTemplate.indexOps(INDEX);
        if (operations.exists()) {
            operations.delete();
        }
    }
}
