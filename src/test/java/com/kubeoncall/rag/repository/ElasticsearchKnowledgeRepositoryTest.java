package com.kubeoncall.rag.repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
    void shouldDeleteDocumentFromConfiguredIndex() {
        ElasticsearchTemplate template = mock(ElasticsearchTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setKnowledgeIndex("kubeoncall-knowledge-test");
        ElasticsearchKnowledgeRepository repository = new ElasticsearchKnowledgeRepository(template, properties);

        repository.deleteById("memory-old");

        verify(template).delete(any(org.springframework.data.elasticsearch.core.query.Query.class), org.mockito.ArgumentMatchers.eq(EsKnowledgeDocumentEntity.class), any());
    }
}
