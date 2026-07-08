package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;
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

    public KnowledgeIngestionFacade(KnowledgeRepository knowledgeRepository,
                                    KnowledgeChunker knowledgeChunker,
                                    KnowledgeObjectStorageService knowledgeObjectStorageService) {
        this.knowledgeRepository = knowledgeRepository;
        this.knowledgeChunker = knowledgeChunker;
        this.knowledgeObjectStorageService = knowledgeObjectStorageService;
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
        chunks.forEach(knowledgeRepository::save);
        return document;
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
