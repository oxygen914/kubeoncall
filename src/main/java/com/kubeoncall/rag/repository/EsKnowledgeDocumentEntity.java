package com.kubeoncall.rag.repository;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Map;
import java.util.List;

@Document(indexName = "kubeoncall-knowledge")
public class EsKnowledgeDocumentEntity {

    @Id
    private String id;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Text)
    private String content;

    @Field(type = FieldType.Keyword)
    private String source;

    @Field(type = FieldType.Flattened)
    private Map<String, String> metadata;

    @Field(type = FieldType.Long)
    private long createdAtEpochMs;

    @Field(name = "embedding_text", type = FieldType.Text, index = false)
    private String embeddingText;

    @Field(type = FieldType.Dense_Vector)
    private List<Float> embedding;

    public EsKnowledgeDocumentEntity() {
    }

    public EsKnowledgeDocumentEntity(String id,
                                     String title,
                                     String content,
                                     String source,
                                     Map<String, String> metadata,
                                     long createdAtEpochMs) {
        this(id, title, content, source, metadata, createdAtEpochMs, null, List.of());
    }

    public EsKnowledgeDocumentEntity(String id,
                                     String title,
                                     String content,
                                     String source,
                                     Map<String, String> metadata,
                                     long createdAtEpochMs,
                                     String embeddingText,
                                     List<Float> embedding) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.source = source;
        this.metadata = metadata;
        this.createdAtEpochMs = createdAtEpochMs;
        this.embeddingText = embeddingText;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public String getEmbeddingText() {
        return embeddingText;
    }

    public List<Float> getEmbedding() {
        return embedding;
    }
}
