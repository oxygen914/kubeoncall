package com.kubeoncall.rag.repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchKnowledgeRepositoryTest {

    @Test
    void shouldSaveDocumentToConfiguredIndex() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setKnowledgeIndex("kubeoncall-knowledge-test");
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);

        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1",
                "title",
                "content",
                "manual",
                Map.of("env", "lab"),
                Instant.now()
        );

        repository.save(document);

        verify(template).save(any(EsKnowledgeDocumentEntity.class), any());
    }

    @Test
    void shouldPrepareVectorMappingBeforeSavingParentWhenLocalEmbeddingEnabled() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KnowledgeIndexAdmin indexAdmin = mock(KnowledgeIndexAdmin.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingEnabled(true);
        properties.getRag().setEmbeddingDimensions(384);
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(
                template, properties, indexAdmin);

        repository.save(new KnowledgeDocument(
                "parent-1", "title", "content", "manual", Map.of(), Instant.now()));

        verify(indexAdmin).ensureVectorMapping(384);
        verify(template).save(any(EsKnowledgeDocumentEntity.class), any());
    }

    @Test
    void shouldDeleteDocumentFromConfiguredIndex() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setKnowledgeIndex("kubeoncall-knowledge-test");
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);

        repository.deleteById("memory-old");

        verify(template).delete(any(org.springframework.data.elasticsearch.core.query.Query.class), org.mockito.ArgumentMatchers.eq(EsKnowledgeDocumentEntity.class), any());
    }

    @Test
    void shouldFindDocumentById() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);
        EsKnowledgeDocumentEntity entity = new EsKnowledgeDocumentEntity(
                "memory-1", "title", "content", "memory", Map.of("source_type", "memory"),
                Instant.now().toEpochMilli());
        when(template.get(eq("memory-1"), eq(EsKnowledgeDocumentEntity.class), any())).thenReturn(entity);

        java.util.Optional<KnowledgeDocument> result = repository.findById("memory-1");

        assertEquals("memory-1", result.orElseThrow().id());
    }

    @Test
    void shouldPreserveElasticsearchHitOrderInsteadOfSortingByCreatedAt() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);
        @SuppressWarnings("unchecked")
        SearchHits<EsKnowledgeDocumentEntity> hits = mock(SearchHits.class);
        @SuppressWarnings("unchecked")
        SearchHit<EsKnowledgeDocumentEntity> relevantOldHit = mock(SearchHit.class);
        @SuppressWarnings("unchecked")
        SearchHit<EsKnowledgeDocumentEntity> lessRelevantNewHit = mock(SearchHit.class);
        EsKnowledgeDocumentEntity relevantOld = new EsKnowledgeDocumentEntity(
                "relevant-old", "CPU runbook", "cpu diagnosis", "manual", Map.of(),
                Instant.parse("2025-01-01T00:00:00Z").toEpochMilli());
        EsKnowledgeDocumentEntity lessRelevantNew = new EsKnowledgeDocumentEntity(
                "less-relevant-new", "other", "other", "manual", Map.of(),
                Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        when(relevantOldHit.getContent()).thenReturn(relevantOld);
        when(lessRelevantNewHit.getContent()).thenReturn(lessRelevantNew);
        when(hits.stream()).thenReturn(Stream.of(relevantOldHit, lessRelevantNewHit));
        when(template.search(
                any(org.springframework.data.elasticsearch.core.query.Query.class),
                eq(EsKnowledgeDocumentEntity.class),
                any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(hits);

        List<KnowledgeDocument> result = repository.searchLexical(
                new RetrievalRequest("cpu", Map.of(), 2), 2);

        assertEquals(List.of("relevant-old", "less-relevant-new"),
                result.stream().map(KnowledgeDocument::id).toList());
    }

    @Test
    void shouldBuildNativeKnnQueryWithVectorAndMetadataFilters() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);
        @SuppressWarnings("unchecked")
        SearchHits<EsKnowledgeDocumentEntity> hits = mock(SearchHits.class);
        when(hits.stream()).thenReturn(Stream.empty());
        when(template.search(
                any(org.springframework.data.elasticsearch.core.query.Query.class),
                eq(EsKnowledgeDocumentEntity.class),
                any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class)))
                .thenReturn(hits);

        repository.searchVector(
                new RetrievalRequest("cpu", Map.of("env", "prod"), 3),
                3,
                List.of(0.1, 0.2, 0.3));

        ArgumentCaptor<org.springframework.data.elasticsearch.core.query.Query> queryCaptor =
                ArgumentCaptor.forClass(org.springframework.data.elasticsearch.core.query.Query.class);
        verify(template).search(
                queryCaptor.capture(),
                eq(EsKnowledgeDocumentEntity.class),
                any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class));
        NativeQuery query = (NativeQuery) queryCaptor.getValue();
        assertEquals("embedding", query.getKnnQuery().field());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), query.getKnnQuery().queryVector());
        assertEquals(12L, query.getKnnQuery().numCandidates());
        assertEquals(3, query.getMaxResults());
        assertEquals(1, query.getKnnQuery().filter().size());
        assertTrue(query.getKnnQuery().filter().get(0).isTerm());
        assertEquals("metadata.env", query.getKnnQuery().filter().get(0).term().field());
    }
}
