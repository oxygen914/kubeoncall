package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeChunker {

    private static final int DEFAULT_CHUNK_SIZE = 280;

    private final KubeOnCallProperties properties;

    public KnowledgeChunker() {
        this(new KubeOnCallProperties());
    }

    @Autowired
    public KnowledgeChunker(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public List<KnowledgeDocument> chunk(KnowledgeDocument sourceDocument) {
        String content = sourceDocument.content() == null ? "" : sourceDocument.content().trim();
        if (content.isBlank()) {
            return List.of(sourceDocument);
        }
        List<String> chunkContents = split(content);
        List<KnowledgeDocument> chunks = new ArrayList<>();
        int totalChunks = chunkContents.size();
        for (int i = 0; i < chunkContents.size(); i++) {
            int chunkNumber = i + 1;
            String chunkId = sourceDocument.id() + "#chunk-" + chunkNumber;
            Map<String, String> metadata = new LinkedHashMap<>();
            if (sourceDocument.metadata() != null) {
                metadata.putAll(sourceDocument.metadata());
            }
            metadata.put("chunk", String.valueOf(chunkNumber));
            metadata.put("parentDocumentId", sourceDocument.id());
            metadata.put("doc_id", sourceDocument.id());
            metadata.put("chunk_id", chunkId);
            metadata.put("chunk_index", String.valueOf(i));
            metadata.put("total_chunks", String.valueOf(totalChunks));
            metadata.put("parent_document_id", sourceDocument.id());
            metadata.put("chunk_enable", "true");
            chunks.add(new KnowledgeDocument(
                    chunkId,
                    sourceDocument.title(),
                    chunkContents.get(i),
                    sourceDocument.source(),
                    metadata,
                    sourceDocument.createdAt()
            ));
        }
        return chunks;
    }

    private List<String> split(String content) {
        int chunkSize = Math.max(32, properties.getRag().getChunkSize() <= 0 ? DEFAULT_CHUNK_SIZE : properties.getRag().getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getRag().getChunkOverlap(), chunkSize / 2));
        String strategy = properties.getRag().getChunkStrategy() == null ? "recursive" : properties.getRag().getChunkStrategy();
        if ("markdown".equalsIgnoreCase(strategy)) {
            List<String> sections = splitMarkdownSections(content);
            if (sections.stream().allMatch(section -> section.length() <= chunkSize)) {
                return sections;
            }
            List<String> chunks = new ArrayList<>();
            for (String section : sections) {
                chunks.addAll(splitRecursive(section, chunkSize, overlap));
            }
            return chunks;
        }
        if ("fixed".equalsIgnoreCase(strategy)) {
            return splitFixed(content, chunkSize, overlap);
        }
        return splitRecursive(content, chunkSize, overlap);
    }

    private List<String> splitMarkdownSections(String content) {
        String[] lines = content.split("\\R");
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("#") && !current.isEmpty()) {
                sections.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().trim());
        }
        return sections.isEmpty() ? List.of(content) : sections.stream().filter(s -> !s.isBlank()).toList();
    }

    private List<String> splitRecursive(String content, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        splitRecursive(content, chunkSize, overlap, chunks);
        return chunks;
    }

    private void splitRecursive(String content, int chunkSize, int overlap, List<String> chunks) {
        String trimmed = content.trim();
        if (trimmed.length() <= chunkSize) {
            if (!trimmed.isBlank()) {
                chunks.add(trimmed);
            }
            return;
        }
        int splitAt = bestSplit(trimmed, chunkSize);
        String left = trimmed.substring(0, splitAt).trim();
        if (!left.isBlank()) {
            chunks.add(left);
        }
        int nextStart = splitAt <= overlap ? splitAt : splitAt - overlap;
        splitRecursive(trimmed.substring(nextStart), chunkSize, overlap, chunks);
    }

    private int bestSplit(String content, int chunkSize) {
        String[] separators = {"\n\n", "\n", ". ", "。", " "};
        for (String separator : separators) {
            int index = content.lastIndexOf(separator, chunkSize);
            if (index > chunkSize / 3) {
                return Math.min(content.length(), index + separator.length());
            }
        }
        return Math.min(content.length(), chunkSize);
    }

    private List<String> splitFixed(String content, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int index = 0;
        while (index < content.length()) {
            int end = Math.min(content.length(), index + chunkSize);
            chunks.add(content.substring(index, end).trim());
            if (end == content.length()) {
                break;
            }
            index = Math.max(index + 1, end - overlap);
        }
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
    }
}
