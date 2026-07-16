package com.kubeoncall.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;

@Component
public class KnowledgeChunker {

    private static final int DEFAULT_CHUNK_SIZE = 280;

    private final KubeOnCallProperties properties;
    private final KnowledgeMetadataFactory metadataFactory;

    public KnowledgeChunker(KubeOnCallProperties properties) {
        this(properties, new KnowledgeMetadataFactory());
    }

    @Autowired
    public KnowledgeChunker(KubeOnCallProperties properties, KnowledgeMetadataFactory metadataFactory) {
        this.properties = properties;
        this.metadataFactory = metadataFactory;
    }

    public List<KnowledgeDocument> chunk(KnowledgeDocument sourceDocument) {
        String content =
                sourceDocument.content() == null ? "" : sourceDocument.content().trim();
        if (content.isBlank()) {
            return List.of(sourceDocument);
        }
        List<ChunkContent> chunkContents = split(content);
        List<KnowledgeDocument> chunks = new ArrayList<>();
        int totalChunks = chunkContents.size();
        for (int i = 0; i < chunkContents.size(); i++) {
            int chunkNumber = i + 1;
            String chunkId = sourceDocument.id() + "#chunk-" + chunkNumber;
            ChunkContent chunkContent = chunkContents.get(i);
            Map<String, String> metadata = metadataFactory.chunkMetadata(
                    sourceDocument.metadata(),
                    sourceDocument.id(),
                    chunkId,
                    i,
                    totalChunks,
                    chunkContent.headingPath());
            chunks.add(new KnowledgeDocument(
                    chunkId,
                    sourceDocument.title(),
                    chunkContent.content(),
                    sourceDocument.source(),
                    metadata,
                    sourceDocument.createdAt()));
        }
        return chunks;
    }

    private List<ChunkContent> split(String content) {
        int chunkSize = Math.max(
                32,
                properties.getRag().getChunkSize() <= 0
                        ? DEFAULT_CHUNK_SIZE
                        : properties.getRag().getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getRag().getChunkOverlap(), chunkSize / 2));
        String strategy = properties.getRag().getChunkStrategy() == null
                ? "recursive"
                : properties.getRag().getChunkStrategy();
        if ("markdown".equalsIgnoreCase(strategy)) {
            List<ChunkContent> sections = splitMarkdownSections(content);
            if (sections.stream().allMatch(section -> section.content().length() <= chunkSize)) {
                return sections;
            }
            List<ChunkContent> chunks = new ArrayList<>();
            for (ChunkContent section : sections) {
                splitRecursive(section.content(), chunkSize, overlap)
                        .forEach(chunk -> chunks.add(new ChunkContent(chunk, section.headingPath())));
            }
            return chunks;
        }
        if ("fixed".equalsIgnoreCase(strategy)) {
            return splitFixed(content, chunkSize, overlap).stream()
                    .map(chunk -> new ChunkContent(chunk, ""))
                    .toList();
        }
        return splitRecursive(content, chunkSize, overlap).stream()
                .map(chunk -> new ChunkContent(chunk, ""))
                .toList();
    }

    private List<ChunkContent> splitMarkdownSections(String content) {
        String[] lines = content.split("\\R");
        List<ChunkContent> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> headings = new ArrayList<>();
        String headingPath = "";
        for (String line : lines) {
            Heading heading = parseHeading(line);
            if (heading != null && !current.isEmpty()) {
                sections.add(new ChunkContent(current.toString().trim(), headingPath));
                current.setLength(0);
            }
            if (heading != null) {
                while (headings.size() >= heading.level()) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(heading.title());
                headingPath = String.join(" / ", headings);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            sections.add(new ChunkContent(current.toString().trim(), headingPath));
        }
        return sections.isEmpty()
                ? List.of(new ChunkContent(content, ""))
                : sections.stream()
                        .filter(section -> !section.content().isBlank())
                        .toList();
    }

    private Heading parseHeading(String line) {
        if (line == null || line.isBlank() || !line.startsWith("#")) {
            return null;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level == line.length() || !Character.isWhitespace(line.charAt(level))) {
            return null;
        }
        String title = line.substring(level).trim();
        return title.isBlank() ? null : new Heading(level, title);
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

    private record ChunkContent(String content, String headingPath) {}

    private record Heading(int level, String title) {}
}
