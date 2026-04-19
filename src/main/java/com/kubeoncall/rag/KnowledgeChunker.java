package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeChunker {

    private static final int CHUNK_SIZE = 280;

    public List<KnowledgeDocument> chunk(KnowledgeDocument sourceDocument) {
        String content = sourceDocument.content() == null ? "" : sourceDocument.content().trim();
        if (content.isBlank()) {
            return List.of(sourceDocument);
        }
        List<KnowledgeDocument> chunks = new ArrayList<>();
        int index = 0;
        int chunkNumber = 1;
        while (index < content.length()) {
            int end = Math.min(content.length(), index + CHUNK_SIZE);
            String chunkContent = content.substring(index, end);
            Map<String, String> metadata = new LinkedHashMap<>();
            if (sourceDocument.metadata() != null) {
                metadata.putAll(sourceDocument.metadata());
            }
            metadata.put("chunk", String.valueOf(chunkNumber));
            metadata.put("parentDocumentId", sourceDocument.id());
            chunks.add(new KnowledgeDocument(
                    sourceDocument.id() + "#chunk-" + chunkNumber,
                    sourceDocument.title(),
                    chunkContent,
                    sourceDocument.source(),
                    metadata,
                    sourceDocument.createdAt()
            ));
            index = end;
            chunkNumber++;
        }
        return chunks;
    }
}
