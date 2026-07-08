package com.kubeoncall.web;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.web.dto.KnowledgeIngestRequest;
import com.kubeoncall.web.dto.KnowledgeQueryRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;

    public KnowledgeController(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    @PostMapping("/ingest")
    public KnowledgeDocument ingest(@Valid @RequestBody KnowledgeIngestRequest request) {
        return knowledgeIngestService.ingest(request.title(), request.content(), request.source(), request.metadata());
    }

    @PostMapping("/query")
    public RetrievalResult query(@Valid @RequestBody KnowledgeQueryRequest request) {
        return knowledgeIngestService.retrieve(
                request.question(),
                request.filters(),
                request.topK(),
                RetrieveMethod.fromRaw(request.retrieveMethod()),
                Boolean.TRUE.equals(request.includeTrace()));
    }
}
