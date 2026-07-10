package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RepositoryVectorRetriever implements VectorRetriever {

    private final KnowledgeRepository knowledgeRepository;
    private final EmbeddingService embeddingService;
    private final KubeOnCallProperties properties;

    public RepositoryVectorRetriever(KnowledgeRepository knowledgeRepository,
                                     EmbeddingService embeddingService,
                                     KubeOnCallProperties properties) {
        this.knowledgeRepository = knowledgeRepository;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return properties.getRag().isVectorEnabled()
                && properties.getRag().isEsKnnEnabled()
                && (properties.getRag().isEmbeddingEnabled() || properties.getRag().isMockEmbeddingEnabled())
                && "es".equalsIgnoreCase(properties.getRag().getVectorBackend());
    }

    @Override
    public List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize) {
        EmbeddingService.EmbeddingResult embedding = embeddingService.embed(request.question());
        List<KnowledgeDocument> documents = knowledgeRepository.searchVector(
                request, candidateSize, embedding.vector());
        return documents.stream()
                .map(document -> withEmbeddingTrace(document, embedding))
                .toList();
    }

    @Override
    public String source() {
        return "elasticsearch_knn";
    }

    private KnowledgeDocument withEmbeddingTrace(KnowledgeDocument document, EmbeddingService.EmbeddingResult embedding) {
        java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("embedding_provider", embedding.provider());
        metadata.put("embedding_model", properties.getRag().getEmbeddingModel());
        metadata.put("embedding_version", properties.getRag().getEmbeddingVersion());
        metadata.put("embedding_mock", String.valueOf(embedding.mock()));
        metadata.put("embedding_dimensions", String.valueOf(embedding.vector().size()));
        return new KnowledgeDocument(
                document.id(),
                document.title(),
                document.content(),
                document.source(),
                metadata,
                document.createdAt(),
                document.embeddingText(),
                document.embedding()
        );
    }
}
