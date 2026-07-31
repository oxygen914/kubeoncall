package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;

class KnowledgeChunkerTest {

    @Test
    void markdownStrategyShouldPreferHeadingSectionsAndStandardMetadata() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setChunkStrategy("markdown");
        properties.getRag().setChunkSize(120);
        KnowledgeChunker chunker = new KnowledgeChunker(properties);
        KnowledgeDocument document = new KnowledgeDocument(
                "doc-1",
                "Payment SOP",
                "# Overview\nshort intro\n## Signals\ncheck metrics\n# Recover\nrestart only after approval",
                "manual",
                Map.of("document_type", "runbook"),
                Instant.now());

        List<KnowledgeDocument> chunks = chunker.chunk(document);

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).content().startsWith("# Overview"));
        assertEquals("doc-1", chunks.get(0).metadata().get("doc_id"));
        assertEquals("doc-1#chunk-1", chunks.get(0).metadata().get("chunk_id"));
        assertEquals("0", chunks.get(0).metadata().get("chunk_index"));
        assertEquals("3", chunks.get(0).metadata().get("total_chunks"));
        assertEquals("doc-1", chunks.get(0).metadata().get("parent_document_id"));
        assertEquals("true", chunks.get(0).metadata().get("chunk_enable"));
        assertEquals("Overview", chunks.get(0).metadata().get("heading_path"));
        assertEquals("Overview / Signals", chunks.get(1).metadata().get("heading_path"));
        assertEquals("Recover", chunks.get(2).metadata().get("heading_path"));
    }
}
