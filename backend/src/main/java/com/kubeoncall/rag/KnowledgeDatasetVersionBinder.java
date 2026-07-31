package com.kubeoncall.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Binds dataset identity to the embedding and augmentation model contract used at ingest time. */
@Component
public class KnowledgeDatasetVersionBinder {

    private final KubeOnCallProperties properties;

    public KnowledgeDatasetVersionBinder(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public void bind(Map<String, String> metadata) {
        if (metadata == null) {
            return;
        }
        String binding = modelBinding();
        metadata.put("dataset_model_binding", binding);
        metadata.put("embedding_model", normalized(properties.getRag().getEmbeddingModel(), "unknown"));
        metadata.put("embedding_version", normalized(properties.getRag().getEmbeddingVersion(), "v1"));
        if (properties.getRag().isAugmentationEnabled()) {
            metadata.put("augmentation_model", normalized(properties.getRag().getAugmentationModel(), "unknown"));
            metadata.put("augmentation_version", normalized(properties.getRag().getAugmentationVersion(), "v1"));
        }
        if (properties.getRag().isAutoBindDatasetVersion()
                && (metadata.get("dataset_version") == null
                        || metadata.get("dataset_version").isBlank())) {
            metadata.put("dataset_version", "modelset-" + digest(binding).substring(0, 12));
        }
    }

    private String modelBinding() {
        String embedding = normalized(properties.getRag().getEmbeddingModel(), "unknown") + "@"
                + normalized(properties.getRag().getEmbeddingVersion(), "v1");
        if (!properties.getRag().isAugmentationEnabled()) {
            return "embedding=" + embedding + ";augmentation=disabled";
        }
        return "embedding=" + embedding + ";augmentation="
                + normalized(properties.getRag().getAugmentationModel(), "unknown") + "@"
                + normalized(properties.getRag().getAugmentationVersion(), "v1");
    }

    private String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to bind knowledge dataset version", ex);
        }
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
