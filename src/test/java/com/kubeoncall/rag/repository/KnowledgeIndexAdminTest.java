package com.kubeoncall.rag.repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexAdminTest {

    @Test
    void shouldCreateDenseVectorMappingWithConfiguredDimensions() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        IndexOperations operations = mock(IndexOperations.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingDimensions(384);
        when(template.indexOps(any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(operations);
        when(operations.exists()).thenReturn(false);
        when(operations.create(eq(Map.of()), any(Document.class))).thenReturn(true);
        KnowledgeIndexAdmin admin = new KnowledgeIndexAdmin(template, properties);

        admin.ensureVectorMapping(384);

        ArgumentCaptor<Document> mappingCaptor = ArgumentCaptor.forClass(Document.class);
        verify(operations).create(eq(Map.of()), mappingCaptor.capture());
        Map<?, ?> fields = (Map<?, ?>) mappingCaptor.getValue().get("properties");
        Map<?, ?> embedding = (Map<?, ?>) fields.get("embedding");
        Map<?, ?> metadata = (Map<?, ?>) fields.get("metadata");
        assertEquals("dense_vector", embedding.get("type"));
        assertEquals(384, embedding.get("dims"));
        assertEquals("cosine", embedding.get("similarity"));
        assertEquals("flattened", metadata.get("type"));
    }

    @Test
    void shouldRejectEmbeddingWithUnexpectedDimensionsBeforeTouchingIndex() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingDimensions(384);
        KnowledgeIndexAdmin admin = new KnowledgeIndexAdmin(template, properties);

        assertThrows(IllegalArgumentException.class, () -> admin.ensureVectorMapping(8));

        verify(template, never()).indexOps(any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class));
    }

    @Test
    void shouldRejectExistingIndexWithDifferentDimensions() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        IndexOperations operations = mock(IndexOperations.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingDimensions(384);
        when(template.indexOps(any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(operations);
        when(operations.exists()).thenReturn(true);
        when(operations.getMapping()).thenReturn(Map.of(
                "properties", Map.of("embedding", Map.of("type", "dense_vector", "dims", 8))));
        KnowledgeIndexAdmin admin = new KnowledgeIndexAdmin(template, properties);

        assertThrows(IllegalStateException.class, () -> admin.ensureVectorMapping(384));

        verify(operations, never()).putMapping(any(Document.class));
    }

    @Test
    void shouldAddMissingVectorAndFlattenedMetadataMappings() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        IndexOperations operations = mock(IndexOperations.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingDimensions(32);
        when(template.indexOps(any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(operations);
        when(operations.exists()).thenReturn(true);
        when(operations.getMapping()).thenReturn(Map.of("properties", Map.of("title", Map.of("type", "text"))));
        when(operations.putMapping(any(Document.class))).thenReturn(true);
        KnowledgeIndexAdmin admin = new KnowledgeIndexAdmin(template, properties);

        admin.ensureVectorMapping(32);

        ArgumentCaptor<Document> mappingCaptor = ArgumentCaptor.forClass(Document.class);
        verify(operations).putMapping(mappingCaptor.capture());
        Map<?, ?> fields = (Map<?, ?>) mappingCaptor.getValue().get("properties");
        assertTrue(fields.containsKey("embedding"));
        assertEquals("flattened", ((Map<?, ?>) fields.get("metadata")).get("type"));
    }

    @Test
    void shouldRejectLegacyObjectMetadataMapping() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        IndexOperations operations = mock(IndexOperations.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingDimensions(32);
        when(template.indexOps(any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(operations);
        when(operations.exists()).thenReturn(true);
        when(operations.getMapping()).thenReturn(Map.of("properties", Map.of(
                "embedding", Map.of("type", "dense_vector", "dims", 32),
                "metadata", Map.of("type", "object"))));
        KnowledgeIndexAdmin admin = new KnowledgeIndexAdmin(template, properties);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> admin.ensureVectorMapping(32));

        assertTrue(error.getMessage().contains("use a new index and reimport"));
        verify(operations, never()).putMapping(any(Document.class));
    }
}
