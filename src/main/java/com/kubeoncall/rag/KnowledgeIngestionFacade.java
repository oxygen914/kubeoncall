package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeIngestionFacade {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeChunker knowledgeChunker;
    private final KnowledgeObjectStorageService knowledgeObjectStorageService;
    private final EmbeddingService embeddingService;
    private final KubeOnCallProperties properties;

    public KnowledgeIngestionFacade(KnowledgeRepository knowledgeRepository,
                                    KnowledgeChunker knowledgeChunker,
                                    KnowledgeObjectStorageService knowledgeObjectStorageService) {
        this(knowledgeRepository, knowledgeChunker, knowledgeObjectStorageService, null, new KubeOnCallProperties());
    }

    @Autowired
    public KnowledgeIngestionFacade(KnowledgeRepository knowledgeRepository,
                                    KnowledgeChunker knowledgeChunker,
                                    KnowledgeObjectStorageService knowledgeObjectStorageService,
                                    EmbeddingService embeddingService,
                                    KubeOnCallProperties properties) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeChunker = knowledgeChunker;
        this.knowledgeObjectStorageService = knowledgeObjectStorageService;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public KnowledgeDocument ingest(String title, String content, String source, Map<String, String> metadata) {
        String documentId = UUID.randomUUID().toString();
        Map<String, String> mergedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }
        StoredDocumentReference reference = knowledgeObjectStorageService.store(title, content, source);
        if (reference.objectKey() != null) {
            mergedMetadata.put("objectKey", reference.objectKey());
        }
        if (reference.bucket() != null) {
            mergedMetadata.put("bucket", reference.bucket());
        }
        mergedMetadata.put("storageStatus", reference.stored() ? "stored" : "skipped");
        mergedMetadata.put("storageMessage", reference.message());
        mergedMetadata.put("doc_id", documentId);
        mergedMetadata.put("chunk_id", documentId);
        mergedMetadata.put("chunk_index", "-1");
        mergedMetadata.put("total_chunks", "0");
        mergedMetadata.put("parent_document_id", "");
        mergedMetadata.put("parentDocumentId", "");
        mergedMetadata.put("document_type", mergedMetadata.getOrDefault("document_type", "runbook"));
        mergedMetadata.put("source_type", mergedMetadata.getOrDefault("source_type", source == null ? "manual" : source));
        mergedMetadata.put("dataset_version", mergedMetadata.getOrDefault("dataset_version", "v1"));
        mergedMetadata.put("chunk_enable", "false");
        mergedMetadata.put("file_hash", sha256(content));

        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                title,
                content,
                source,
                mergedMetadata,
                Instant.now()
        );
        knowledgeRepository.save(document);
        List<KnowledgeDocument> chunks = knowledgeChunker.chunk(document);
        chunks.stream()
                .map(this::withEmbedding)
                .forEach(knowledgeRepository::save);
        return document;
    }

    private KnowledgeDocument withEmbedding(KnowledgeDocument chunk) {
        if (embeddingService == null || !embeddingConfigured() || !isEnabledChunk(chunk)) {
            return chunk;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (chunk.metadata() != null) {
            metadata.putAll(chunk.metadata());
        }
        try {
            EmbeddingService.EmbeddingResult result = embeddingService.embed(chunk.content());
            int configuredDimensions = Math.max(1, properties.getRag().getEmbeddingDimensions());
            if (result.vector().size() != configuredDimensions) {
                throw new IllegalStateException(
                        "Embedding dimensions " + result.vector().size()
                                + " do not match configured dimensions " + configuredDimensions);
            }
            metadata.put("embedding_status", "ready");
            metadata.put("embedding_model", properties.getRag().getEmbeddingModel());
            metadata.put("embedding_version", properties.getRag().getEmbeddingVersion());
            metadata.put("embedding_provider", result.provider());
            metadata.put("embedding_dimensions", String.valueOf(result.vector().size()));
            metadata.put("embedding_mock", String.valueOf(result.mock()));
            return new KnowledgeDocument(
                    chunk.id(), chunk.title(), chunk.content(), chunk.source(), metadata, chunk.createdAt(),
                    chunk.content(), result.vector());
        } catch (RuntimeException ex) {
            metadata.put("embedding_status", "failed");
            metadata.put("embedding_model", properties.getRag().getEmbeddingModel());
            metadata.put("embedding_version", properties.getRag().getEmbeddingVersion());
            metadata.put("embedding_error", ex.getClass().getSimpleName());
            return new KnowledgeDocument(
                    chunk.id(), chunk.title(), chunk.content(), chunk.source(), metadata, chunk.createdAt());
        }
    }

    private boolean embeddingConfigured() {
        return "es".equalsIgnoreCase(properties.getRag().getVectorBackend())
                && (properties.getRag().isEmbeddingEnabled() || properties.getRag().isMockEmbeddingEnabled());
    }

    private boolean isEnabledChunk(KnowledgeDocument chunk) {
        return chunk.metadata() != null
                && "true".equalsIgnoreCase(chunk.metadata().get("chunk_enable"));
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash knowledge content", ex);
        }
    }
}
