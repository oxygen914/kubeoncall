package com.kubeoncall.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.rag.EmbeddingService;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Combines embedding cosine similarity with lexical overlap for memory consolidation. */
@Service
public class MemorySimilarityService {

    private final KubeOnCallProperties properties;
    private final EmbeddingService embeddingService;
    private final KubeOnCallMetricsService metricsService;
    private final Map<String, List<Double>> vectorCache =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Double>> eldest) {
                    return size() > 1000;
                }
            });

    public MemorySimilarityService(
            KubeOnCallProperties properties,
            EmbeddingService embeddingService,
            KubeOnCallMetricsService metricsService) {
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.metricsService = metricsService;
    }

    public double similarity(KnowledgeDocument left, KnowledgeDocument right, double lexicalSimilarity) {
        if (!properties.getMemory().isSemanticDuplicateEnabled()) {
            return lexicalSimilarity;
        }
        try {
            List<Double> leftVector = vector(left);
            List<Double> rightVector = vector(right);
            double semantic = cosine(leftVector, rightVector);
            double weight = Math.max(0.0d, Math.min(1.0d, properties.getMemory().getSemanticDuplicateWeight()));
            metricsService.recordMemory("semantic_compare", "success", 1);
            return weight * semantic + (1.0d - weight) * lexicalSimilarity;
        } catch (RuntimeException ex) {
            metricsService.recordMemory("semantic_compare", "fallback", 1);
            return lexicalSimilarity;
        }
    }

    private List<Double> vector(KnowledgeDocument document) {
        if (document.embedding() != null && !document.embedding().isEmpty()) {
            return document.embedding();
        }
        String key = properties.getRag().getEmbeddingModel() + "@"
                + properties.getRag().getEmbeddingVersion() + ":" + document.content();
        synchronized (vectorCache) {
            List<Double> cached = vectorCache.get(key);
            if (cached != null) {
                return cached;
            }
            List<Double> embedded = embeddingService.embed(document.content()).vector();
            vectorCache.put(key, embedded);
            return embedded;
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            throw new IllegalArgumentException("Memory vectors are incompatible");
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
