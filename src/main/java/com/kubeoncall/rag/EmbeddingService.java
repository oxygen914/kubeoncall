package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private final List<EmbeddingClient> embeddingClients;
    private final KubeOnCallProperties properties;

    public EmbeddingService(List<EmbeddingClient> embeddingClients, KubeOnCallProperties properties) {
        this.embeddingClients = embeddingClients == null ? List.of() : embeddingClients;
        this.properties = properties;
    }

    public EmbeddingResult embed(String text) {
        if (properties.getRag().isMockEmbeddingEnabled()) {
            return new EmbeddingResult(deterministicEmbedding(text), "deterministic_mock", true);
        }
        if (!properties.getRag().isEmbeddingEnabled()) {
            throw new IllegalStateException("Embedding is disabled");
        }
        for (EmbeddingClient client : embeddingClients) {
            if (!client.available()) {
                continue;
            }
            List<Double> vector = client.embed(text);
            if (vector != null && !vector.isEmpty()) {
                return new EmbeddingResult(vector, client.provider(), false);
            }
        }
        throw new IllegalStateException("No available embedding client");
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

    public record EmbeddingResult(
            List<Double> vector,
            String provider,
            boolean mock
    ) {
    }
}
