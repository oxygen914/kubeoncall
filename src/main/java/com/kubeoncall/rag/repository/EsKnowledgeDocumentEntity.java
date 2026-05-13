package com.kubeoncall.rag.repository;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Map;

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

    @Field(type = FieldType.Object)
    private Map<String, String> metadata;

    @Field(type = FieldType.Long)
    private long createdAtEpochMs;

    public EsKnowledgeDocumentEntity() {
    }

    public EsKnowledgeDocumentEntity(String id,
                                     String title,
                                     String content,
                                     String source,
                                     Map<String, String> metadata,
                                     long createdAtEpochMs) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.source = source;
        this.metadata = metadata;
        this.createdAtEpochMs = createdAtEpochMs;
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
}
