package com.kubeoncall.rag.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Repository;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.domain.rag.RetrievalRequest;

import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

@Repository
@Primary
public class ElasticsearchKnowledgeRepository implements KnowledgeRepository {

    private final ElasticsearchTemplate elasticsearchTemplate;
    private final KubeOnCallProperties properties;
    private final KnowledgeIndexAdmin indexAdmin;

    public ElasticsearchKnowledgeRepository(
            ElasticsearchTemplate elasticsearchTemplate,
            KubeOnCallProperties properties,
            KnowledgeIndexAdmin indexAdmin) {
        this.elasticsearchTemplate = elasticsearchTemplate;
        this.properties = properties;
        this.indexAdmin = indexAdmin;
    }

    @Override
    public void save(KnowledgeDocument document) {
        if (shouldPrepareVectorIndex(document)) {
            int dimensions =
                    document.embedding() == null || document.embedding().isEmpty()
                            ? Math.max(1, properties.getRag().getEmbeddingDimensions())
                            : document.embedding().size();
            indexAdmin.ensureVectorMapping(dimensions);
        }
        EsKnowledgeDocumentEntity entity = toEntity(document);
        elasticsearchTemplate.save(entity, index());
    }

    @Override
    public List<KnowledgeDocument> searchLexical(RetrievalRequest request, int candidateSize) {
        return searchLexicalHits(request, candidateSize).stream()
                .map(RetrievalHit::document)
                .toList();
    }

    @Override
    public List<RetrievalHit> searchLexicalHits(RetrievalRequest request, int candidateSize) {
        if (!indexAdmin.indexExists()) {
            return List.of();
        }
        Criteria criteria = buildLexicalCriteria(request);
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setMaxResults(Math.max(1, candidateSize));
        return searchHitsByQuery(query, "LEXICAL");
    }

    @Override
    public List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize) {
        throw new IllegalStateException("Query embedding is required for Elasticsearch vector search");
    }

    @Override
    public List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize, List<Double> queryVector) {
        return searchVectorHits(request, candidateSize, queryVector).stream()
                .map(RetrievalHit::document)
                .toList();
    }

    @Override
    public List<RetrievalHit> searchVectorHits(RetrievalRequest request, int candidateSize, List<Double> queryVector) {
        if (queryVector == null || queryVector.isEmpty()) {
            return List.of();
        }
        indexAdmin.ensureVectorMapping(queryVector.size());
        List<Float> vector = queryVector.stream().map(Double::floatValue).toList();
        List<Query> filters = vectorFilters(request);
        KnnQuery.Builder knnBuilder = new KnnQuery.Builder()
                .field("embedding")
                .queryVector(vector)
                .numCandidates(Math.min(10_000L, Math.max((long) candidateSize, (long) candidateSize * 4L)));
        if (!filters.isEmpty()) {
            knnBuilder.filter(filters);
        }
        NativeQuery query = NativeQuery.builder()
                .withKnnQuery(knnBuilder.build())
                .withMaxResults(Math.max(1, candidateSize))
                .build();
        return searchHitsByQuery(query, "VECTOR");
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

    @Override
    public Optional<KnowledgeDocument> findById(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        EsKnowledgeDocumentEntity entity =
                elasticsearchTemplate.get(documentId.trim(), EsKnowledgeDocumentEntity.class, index());
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<KnowledgeDocument> findByMetadata(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank() || !indexAdmin.indexExists()) {
            return List.of();
        }
        CriteriaQuery query = new CriteriaQuery(new Criteria("metadata." + key.trim()).is(value.trim()));
        query.setMaxResults(10_000);
        return searchByQuery(query);
    }

    @Override
    public void deleteById(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        CriteriaQuery query = new CriteriaQuery(new Criteria("id").is(documentId));
        elasticsearchTemplate.delete(query, EsKnowledgeDocumentEntity.class, index());
    }

    private Criteria buildLexicalCriteria(RetrievalRequest request) {
        Criteria criteria = new Criteria();
        String normalizedQuery = normalizeQuestion(request);
        if (!normalizedQuery.isBlank()) {
            Criteria titleCriteria = new Criteria("title").matches(normalizedQuery);
            Criteria contentCriteria = new Criteria("content").matches(normalizedQuery);
            criteria = criteria.subCriteria(titleCriteria.or(contentCriteria));
        }
        Criteria filtered = appendFilters(criteria, request);
        if (request == null || request.filters() == null || !request.filters().containsKey("chunk_enable")) {
            filtered = filtered.and(new Criteria("metadata.chunk_enable").is("true"));
        }
        return filtered;
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

    private List<Query> vectorFilters(RetrievalRequest request) {
        if (request == null || request.filters() == null || request.filters().isEmpty()) {
            return List.of();
        }
        return request.filters().entrySet().stream()
                .map(entry -> Query.of(query -> query.term(
                        term -> term.field("metadata." + entry.getKey()).value(entry.getValue()))))
                .toList();
    }

    private boolean shouldPrepareVectorIndex(KnowledgeDocument document) {
        boolean hasEmbedding =
                document.embedding() != null && !document.embedding().isEmpty();
        boolean localEmbeddingConfigured =
                "es".equalsIgnoreCase(properties.getRag().getVectorBackend())
                        && (properties.getRag().isEmbeddingEnabled()
                                || properties.getRag().isMockEmbeddingEnabled());
        return hasEmbedding || localEmbeddingConfigured;
    }

    private List<KnowledgeDocument> searchByQuery(org.springframework.data.elasticsearch.core.query.Query query) {
        SearchHits<EsKnowledgeDocumentEntity> hits =
                elasticsearchTemplate.search(query, EsKnowledgeDocumentEntity.class, index());
        return hits.stream().map(SearchHit::getContent).map(this::toDomain).toList();
    }

    private List<RetrievalHit> searchHitsByQuery(
            org.springframework.data.elasticsearch.core.query.Query query, String channel) {
        SearchHits<EsKnowledgeDocumentEntity> hits =
                elasticsearchTemplate.search(query, EsKnowledgeDocumentEntity.class, index());
        java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger(1);
        return hits.stream()
                .map(hit -> new RetrievalHit(
                        toDomain(hit.getContent()), (double) hit.getScore(), rank.getAndIncrement(), channel))
                .toList();
    }

    private EsKnowledgeDocumentEntity toEntity(KnowledgeDocument document) {
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        Instant createdAt = document.createdAt() == null ? Instant.now() : document.createdAt();
        String title = document.title() == null ? "" : document.title();
        String content = document.content() == null ? "" : document.content();
        String source = document.source() == null ? "manual" : document.source().toLowerCase(Locale.ROOT);
        List<Float> embedding =
                document.embedding() == null || document.embedding().isEmpty()
                        ? null
                        : document.embedding().stream().map(Double::floatValue).toList();
        return new EsKnowledgeDocumentEntity(
                document.id(),
                title,
                content,
                source,
                metadata,
                createdAt.toEpochMilli(),
                document.embeddingText(),
                embedding);
    }

    private KnowledgeDocument toDomain(EsKnowledgeDocumentEntity entity) {
        return new KnowledgeDocument(
                entity.getId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getSource(),
                entity.getMetadata(),
                Instant.ofEpochMilli(entity.getCreatedAtEpochMs()),
                entity.getEmbeddingText(),
                entity.getEmbedding() == null
                        ? List.of()
                        : entity.getEmbedding().stream().map(Float::doubleValue).toList());
    }

    private IndexCoordinates index() {
        String alias = properties.getRag().getKnowledgeIndexAlias();
        return IndexCoordinates.of(
                alias == null || alias.isBlank() ? properties.getRag().getKnowledgeIndex() : alias.trim());
    }
}
