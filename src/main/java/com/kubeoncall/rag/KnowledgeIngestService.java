package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class KnowledgeIngestService {

    private final KnowledgeIngestionFacade ingestionFacade;
    private final KnowledgeRetrievalFacade retrievalFacade;

    @Autowired
    public KnowledgeIngestService(KnowledgeIngestionFacade ingestionFacade,
                                  KnowledgeRetrievalFacade retrievalFacade) {
        this.ingestionFacade = ingestionFacade;
        this.retrievalFacade = retrievalFacade;
    }

    public KnowledgeIngestService(KnowledgeRepository knowledgeRepository,
                                  QueryRewriteService queryRewriteService,
                                  RagRouter ragRouter,
                                  HybridRetrievalService hybridRetrievalService,
                                  RerankService rerankService,
                                  KubeOnCallProperties properties,
                                  KnowledgeChunker knowledgeChunker,
                                  KnowledgeObjectStorageService knowledgeObjectStorageService) {
        this(
                new KnowledgeIngestionFacade(knowledgeRepository, knowledgeChunker, knowledgeObjectStorageService),
                new KnowledgeRetrievalFacade(knowledgeRepository, queryRewriteService, ragRouter, hybridRetrievalService, rerankService, properties)
        );
    }

    public KnowledgeDocument ingest(String title, String content, String source, Map<String, String> metadata) {
        return ingestionFacade.ingest(title, content, source, metadata);
    }

    public RetrievalResult retrieve(String question, Map<String, String> filters) {
        return retrievalFacade.retrieve(question, filters);
    }

    public RetrievalResult retrieve(String question,
                                    Map<String, String> filters,
                                    Integer topK,
                                    RetrieveMethod retrieveMethod,
                                    boolean includeTrace) {
        return retrievalFacade.retrieve(question, filters, topK, retrieveMethod, includeTrace);
    }
}
