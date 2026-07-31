package com.kubeoncall.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class EmbeddingService {

    private final List<EmbeddingClient> embeddingClients;
    private final KubeOnCallProperties properties;

    public EmbeddingService(List<EmbeddingClient> embeddingClients, KubeOnCallProperties properties) {
        this.embeddingClients = embeddingClients == null ? List.of() : embeddingClients;
        this.properties = properties;
    }

    public EmbeddingResult embed(String text) {
        List<EmbeddingResult> batch = embedBatch(List.of(text == null ? "" : text));
        if (batch.isEmpty()) {
            throw new IllegalStateException("Embedding provider returned no result");
        }
        return batch.get(0);
    }

    public List<EmbeddingResult> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (properties.getRag().isMockEmbeddingEnabled()) {
            return texts.stream()
                    .map(this::deterministicEmbedding)
                    .map(vector -> validated(vector, "deterministic_mock", true))
                    .toList();
        }
        if (!properties.getRag().isEmbeddingEnabled()) {
            throw new IllegalStateException("Embedding is disabled");
        }
        for (EmbeddingClient client : embeddingClients) {
            if (!client.available()) {
                continue;
            }
            List<EmbeddingResult> results = embedBatches(client, texts);
            if (results.size() == texts.size()) {
                return results;
            }
        }
        throw new IllegalStateException("No available embedding client");
    }

    private List<EmbeddingResult> embedBatches(EmbeddingClient client, List<String> texts) {
        int batchSize = Math.max(1, properties.getRag().getEmbeddingBatchSize());
        List<EmbeddingResult> results = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<List<Double>> vectors = client.embedBatch(texts.subList(start, end));
            if (vectors == null || vectors.size() != end - start) {
                return List.of();
            }
            vectors.stream()
                    .map(vector -> validated(vector, client.provider(), false))
                    .forEach(results::add);
        }
        return List.copyOf(results);
    }

    private EmbeddingResult validated(List<Double> vector, String provider, boolean mock) {
        int expectedDimensions = Math.max(1, properties.getRag().getEmbeddingDimensions());
        if (vector.size() != expectedDimensions) {
            throw new IllegalStateException("Embedding dimensions " + vector.size()
                    + " do not match configured dimensions " + expectedDimensions);
        }
        if (vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalStateException("Embedding contains non-finite values");
        }
        return new EmbeddingResult(List.copyOf(vector), provider, mock);
    }

    private List<Double> deterministicEmbedding(String text) {
        int dimensions = Math.max(1, properties.getRag().getEmbeddingDimensions());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            List<Double> vector = new ArrayList<>(dimensions);
            for (int i = 0; i < dimensions; i++) {
                int unsigned = hash[i % hash.length] & 0xff;
                vector.add(unsigned / 255.0d);
            }
            return vector;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create deterministic embedding", ex);
        }
    }

    public record EmbeddingResult(List<Double> vector, String provider, boolean mock) {}
}
