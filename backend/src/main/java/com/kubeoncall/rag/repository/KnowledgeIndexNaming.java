package com.kubeoncall.rag.repository;

import java.util.Locale;

import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Centralizes knowledge index naming, version validation, and relevant configuration defaults. */
@Component
public class KnowledgeIndexNaming {

    private final KubeOnCallProperties properties;

    public KnowledgeIndexNaming(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public boolean aliasEnabled() {
        return properties.getRag().getKnowledgeIndexAlias() != null
                && !properties.getRag().getKnowledgeIndexAlias().isBlank();
    }

    public String aliasName() {
        requireAlias();
        return properties.getRag().getKnowledgeIndexAlias().trim();
    }

    public void requireAlias() {
        if (!aliasEnabled()) {
            throw new IllegalStateException("knowledge-index-alias is not configured");
        }
    }

    public String normalizeVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        String version = rawVersion.trim().toLowerCase(Locale.ROOT);
        if (!version.matches("v?[a-z0-9][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("version must contain only letters, digits, dot, underscore or hyphen");
        }
        return version.startsWith("v") ? version : "v" + version;
    }

    public String versionedIndex(String version) {
        return properties.getRag().getKnowledgeIndex() + "-" + normalizeVersion(version);
    }

    public int configuredDimensions() {
        return Math.max(1, properties.getRag().getEmbeddingDimensions());
    }

    public IndexCoordinates activeIndex() {
        return IndexCoordinates.of(
                aliasEnabled() ? aliasName() : properties.getRag().getKnowledgeIndex());
    }
}
