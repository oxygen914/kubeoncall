package com.kubeoncall.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;

@Service
public class KnowledgeIngestionFacade {

    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeChunker knowledgeChunker;
    private final KnowledgeObjectStorageService knowledgeObjectStorageService;
    private final EmbeddingService embeddingService;
    private final KubeOnCallProperties properties;
    private final KnowledgeMetadataFactory metadataFactory;

    public KnowledgeIngestionFacade(
            KnowledgeRepository knowledgeRepository,
            KnowledgeChunker knowledgeChunker,
            KnowledgeObjectStorageService knowledgeObjectStorageService,
            EmbeddingService embeddingService,
            KubeOnCallProperties properties) {
        this(
                knowledgeRepository,
                knowledgeChunker,
                knowledgeObjectStorageService,
                embeddingService,
                properties,
                new KnowledgeMetadataFactory());
    }

    @Autowired
    public KnowledgeIngestionFacade(
            KnowledgeRepository knowledgeRepository,
            KnowledgeChunker knowledgeChunker,
            KnowledgeObjectStorageService knowledgeObjectStorageService,
            EmbeddingService embeddingService,
            KubeOnCallProperties properties,
            KnowledgeMetadataFactory metadataFactory) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeChunker = knowledgeChunker;
        this.knowledgeObjectStorageService = knowledgeObjectStorageService;
        this.embeddingService = embeddingService;
        this.properties = properties;
        this.metadataFactory = metadataFactory;
    }

    public KnowledgeDocument ingest(String title, String content, String source, Map<String, String> metadata) {
        return ingestWithResult(title, content, source, metadata).document();
    }

    public IngestionResult ingestWithResult(String title, String content, String source, Map<String, String> metadata) {
        Map<String, String> mergedMetadata = metadataFactory.copy(metadata);
        String fileHash = sha256(content);
        String requestedDocumentId = normalize(mergedMetadata.get("doc_id"));
        if (requestedDocumentId.isBlank()) {
            KnowledgeDocument duplicate = findExistingByHash(fileHash);
            if (duplicate != null) {
                return new IngestionResult(duplicate, "duplicate", 0, false);
            }
        }
        String documentId = requestedDocumentId.isBlank() ? UUID.randomUUID().toString() : requestedDocumentId;
        List<KnowledgeDocument> existingDocuments = knowledgeRepository.findByMetadata("doc_id", documentId);
        preserveCreatedAt(mergedMetadata, existingDocuments, documentId);
        StoredDocumentReference reference = knowledgeObjectStorageService.store(title, content, source);
        Instant now = Instant.now();
        Map<String, String> parentMetadata =
                metadataFactory.parentMetadata(mergedMetadata, documentId, source, fileHash, reference, now);

        KnowledgeDocument document = new KnowledgeDocument(documentId, title, content, source, parentMetadata, now);
        knowledgeRepository.save(document);
        List<KnowledgeDocument> chunks = knowledgeChunker.chunk(document);
        chunks.stream().map(this::withEmbedding).forEach(knowledgeRepository::save);
        removeStaleChunks(existingDocuments, documentId, chunks);
        return new IngestionResult(
                document, existingDocuments.isEmpty() ? "created" : "updated", chunks.size(), reference.stored());
    }

    public LifecycleResult softDelete(String documentId, String reason) {
        return changeAvailability(documentId, false, reason);
    }

    public LifecycleResult restore(String documentId) {
        return changeAvailability(documentId, true, null);
    }

    private KnowledgeDocument findExistingByHash(String fileHash) {
        return knowledgeRepository.findByMetadata("file_hash", fileHash).stream()
                .filter(document -> isParent(
                        document,
                        document.metadata() == null ? "" : document.metadata().get("doc_id")))
                .findFirst()
                .orElse(null);
    }

    private void removeStaleChunks(
            List<KnowledgeDocument> existingDocuments, String documentId, List<KnowledgeDocument> chunks) {
        if (existingDocuments == null || existingDocuments.isEmpty()) {
            return;
        }
        java.util.Set<String> currentIds = new java.util.HashSet<>();
        currentIds.add(documentId);
        chunks.forEach(chunk -> currentIds.add(chunk.id()));
        existingDocuments.stream()
                .map(KnowledgeDocument::id)
                .filter(id -> id != null && !currentIds.contains(id))
                .forEach(knowledgeRepository::deleteById);
    }

    private void preserveCreatedAt(
            Map<String, String> metadata, List<KnowledgeDocument> existingDocuments, String documentId) {
        if (metadata.containsKey("created_at") || existingDocuments == null) {
            return;
        }
        existingDocuments.stream()
                .filter(document -> isParent(document, documentId))
                .map(KnowledgeDocument::metadata)
                .filter(existingMetadata -> existingMetadata != null)
                .map(existingMetadata -> existingMetadata.get("created_at"))
                .filter(createdAt -> createdAt != null && !createdAt.isBlank())
                .findFirst()
                .ifPresent(createdAt -> metadata.put("created_at", createdAt));
    }

    private LifecycleResult changeAvailability(String documentId, boolean restore, String reason) {
        String normalizedDocumentId = normalize(documentId);
        if (normalizedDocumentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        List<KnowledgeDocument> documents = knowledgeRepository.findByMetadata("doc_id", normalizedDocumentId);
        if (documents.isEmpty()) {
            return new LifecycleResult(normalizedDocumentId, restore ? "restored" : "deleted", 0, false);
        }
        Instant now = Instant.now();
        for (KnowledgeDocument document : documents) {
            Map<String, String> documentMetadata = metadataFactory.copy(document.metadata());
            if (restore) {
                documentMetadata.put("chunk_enable", isParent(document, normalizedDocumentId) ? "false" : "true");
                documentMetadata.remove("deleted_at");
                documentMetadata.remove("delete_reason");
            } else {
                documentMetadata.put("chunk_enable", "false");
                documentMetadata.put("deleted_at", now.toString());
                documentMetadata.put("delete_reason", normalize(reason).isBlank() ? "manual" : normalize(reason));
            }
            documentMetadata.put("updated_at", now.toString());
            knowledgeRepository.save(new KnowledgeDocument(
                    document.id(),
                    document.title(),
                    document.content(),
                    document.source(),
                    documentMetadata,
                    document.createdAt(),
                    document.embeddingText(),
                    document.embedding()));
        }
        return new LifecycleResult(normalizedDocumentId, restore ? "restored" : "deleted", documents.size(), true);
    }

    private boolean isParent(KnowledgeDocument document, String documentId) {
        if (document == null) {
            return false;
        }
        if (documentId != null && documentId.equals(document.id())) {
            return true;
        }
        Map<String, String> documentMetadata = document.metadata();
        return documentMetadata != null && "-1".equals(documentMetadata.get("chunk_index"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private KnowledgeDocument withEmbedding(KnowledgeDocument chunk) {
        if (!embeddingConfigured() || !isEnabledChunk(chunk)) {
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
                throw new IllegalStateException("Embedding dimensions "
                        + result.vector().size() + " do not match configured dimensions " + configuredDimensions);
            }
            metadata.put("embedding_status", "ready");
            metadata.put("embedding_model", properties.getRag().getEmbeddingModel());
            metadata.put("embedding_version", properties.getRag().getEmbeddingVersion());
            metadata.put("embedding_provider", result.provider());
            metadata.put("embedding_dimensions", String.valueOf(result.vector().size()));
            metadata.put("embedding_mock", String.valueOf(result.mock()));
            return new KnowledgeDocument(
                    chunk.id(),
                    chunk.title(),
                    chunk.content(),
                    chunk.source(),
                    metadata,
                    chunk.createdAt(),
                    chunk.content(),
                    result.vector());
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
                && (properties.getRag().isEmbeddingEnabled()
                        || properties.getRag().isMockEmbeddingEnabled());
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

    public record LifecycleResult(String documentId, String operation, int affected, boolean found) {}

    public record IngestionResult(KnowledgeDocument document, String operation, int chunkCount, boolean sourceStored) {}
}
