package com.kubeoncall.rag.repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Repository
@Primary
public class ElasticsearchKnowledgeRepository implements KnowledgeRepository {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KubeOnCallProperties properties;

    public ElasticsearchKnowledgeRepository(ElasticsearchTemplate elasticsearchTemplate,
                                            KubeOnCallProperties properties) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.properties = properties;
    }

    @Override
    public void save(KnowledgeDocument document) {
        EsKnowledgeDocumentEntity entity = toEntity(document);
        elasticsearchTemplate.save(entity, index());
    }

    @Override
    public List<KnowledgeDocument> searchLexical(RetrievalRequest request, int candidateSize) {
        Criteria criteria = buildLexicalCriteria(request);
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setMaxResults(Math.max(1, candidateSize));
        return searchByQuery(query);
    }

    @Override
    public List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize) {
        Criteria criteria = buildVectorCriteria(request);
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setMaxResults(Math.max(1, candidateSize));
        return searchByQuery(query);
    }

    @Override
    public Map<String, KnowledgeDocument> loadParents(List<String> parentDocumentIds) {
        if (parentDocumentIds == null || parentDocumentIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = parentDocumentIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        CriteriaQuery query = new CriteriaQuery(new Criteria("id").in(ids));
        query.setMaxResults(ids.size());
        List<KnowledgeDocument> parents = searchByQuery(query);
        Map<String, KnowledgeDocument> mapped = new LinkedHashMap<>();
        for (KnowledgeDocument parent : parents) {
            mapped.put(parent.id(), parent);
        }
        return mapped;
    }

    private Criteria buildLexicalCriteria(RetrievalRequest request) {
        Criteria criteria = new Criteria();
        String normalizedQuery = normalizeQuestion(request);
        if (!normalizedQuery.isBlank()) {
            Criteria titleCriteria = new Criteria("title").matches(normalizedQuery);
            Criteria contentCriteria = new Criteria("content").matches(normalizedQuery);
            criteria = criteria.subCriteria(titleCriteria.or(contentCriteria));
        }
        return appendFilters(criteria, request);
    }

    private Criteria buildVectorCriteria(RetrievalRequest request) {
        Criteria criteria = new Criteria();
        String normalizedQuery = normalizeQuestion(request);
        if (!normalizedQuery.isBlank()) {
            Criteria contentCriteria = new Criteria("content").matches(normalizedQuery);
            criteria = criteria.subCriteria(contentCriteria);
        }
        return appendFilters(criteria, request);
    }

    private String normalizeQuestion(RetrievalRequest request) {
        if (request == null || request.question() == null) {
            return "";
        }
        return request.question().trim();
    }

    private Criteria appendFilters(Criteria criteria, RetrievalRequest request) {
        if (request == null || request.filters() == null || request.filters().isEmpty()) {
            return criteria;
        }
        Criteria merged = criteria;
        for (Map.Entry<String, String> entry : request.filters().entrySet()) {
            merged = merged.and(new Criteria("metadata." + entry.getKey()).is(entry.getValue()));
        }
        return merged;
    }

    private List<KnowledgeDocument> searchByQuery(CriteriaQuery query) {
        SearchHits<EsKnowledgeDocumentEntity> hits = elasticsearchTemplate.search(query, EsKnowledgeDocumentEntity.class, index());
        return hits.stream()
                .map(SearchHit::getContent)
                .map(this::toDomain)
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    private EsKnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        Instant createdAt = document.createdAt() == null ? Instant.now() : document.createdAt();
        String title = document.title() == null ? "" : document.title();
        String content = document.content() == null ? "" : document.content();
        String source = document.source() == null ? "manual" : document.source().toLowerCase(Locale.ROOT);
        return new EsKnowledgeDocumentEntity(document.id(), title, content, source, metadata, createdAt.toEpochMilli());
    }

    private KnowledgeDocument toDomain(EsKnowledgeDocumentEntity entity) {
        return new KnowledgeDocument(
                entity.getId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getSource(),
                entity.getMetadata(),
                Instant.ofEpochMilli(entity.getCreatedAtEpochMs())
        );
    }

    private IndexCoordinates index() {
        return IndexCoordinates.of(properties.getRag().getKnowledgeIndex());
    }
}
