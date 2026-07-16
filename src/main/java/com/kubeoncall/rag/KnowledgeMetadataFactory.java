package com.kubeoncall.rag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.storage.StoredDocumentReference;

/** Centralizes the metadata contract shared by parent documents and retrieval chunks. */
@Component
public class KnowledgeMetadataFactory {

    public Map<String, String> parentMetadata(
            Map<String, String> input,
            String documentId,
            String source,
            String fileHash,
            StoredDocumentReference reference,
            Instant now) {
        Map<String, String> metadata = copy(input);
        if (reference != null && reference.objectKey() != null) {
            metadata.put("objectKey", reference.objectKey());
        }
        if (reference != null && reference.bucket() != null) {
            metadata.put("bucket", reference.bucket());
        }
        metadata.put("storageStatus", reference != null && reference.stored() ? "stored" : "skipped");
        metadata.put("storageMessage", reference == null ? "storage reference unavailable" : reference.message());
        metadata.put("doc_id", documentId);
        metadata.put("chunk_id", documentId);
        metadata.put("chunk_index", "-1");
        metadata.put("total_chunks", "0");
        metadata.put("parent_document_id", "");
        metadata.put("parentDocumentId", "");
        metadata.put("document_type", metadata.getOrDefault("document_type", "runbook"));
        metadata.put("source_type", metadata.getOrDefault("source_type", source == null ? "manual" : source));
        metadata.put("dataset_version", metadata.getOrDefault("dataset_version", "v1"));
        metadata.put("chunk_enable", "false");
        metadata.put("file_hash", fileHash);
        metadata.put("created_at", metadata.getOrDefault("created_at", now.toString()));
        metadata.put("updated_at", now.toString());
        return metadata;
    }

    public Map<String, String> chunkMetadata(
            Map<String, String> parentMetadata,
            String documentId,
            String chunkId,
            int chunkIndex,
            int totalChunks,
            String headingPath) {
        Map<String, String> metadata = copy(parentMetadata);
        metadata.put("chunk", String.valueOf(chunkIndex + 1));
        metadata.put("parentDocumentId", documentId);
        metadata.put("doc_id", documentId);
        metadata.put("chunk_id", chunkId);
        metadata.put("chunk_index", String.valueOf(chunkIndex));
        metadata.put("total_chunks", String.valueOf(totalChunks));
        metadata.put("parent_document_id", documentId);
        metadata.put("chunk_enable", "true");
        if (headingPath == null || headingPath.isBlank()) {
            metadata.remove("heading_path");
        } else {
            metadata.put("heading_path", headingPath);
        }
        return metadata;
    }

    public Map<String, String> copy(Map<String, String> metadata) {
        Map<String, String> copied = new LinkedHashMap<>();
        if (metadata != null) {
            copied.putAll(metadata);
        }
        return copied;
    }
}
