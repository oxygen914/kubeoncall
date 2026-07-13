package com.kubeoncall.rag.repository;

import java.util.List;

import org.springframework.stereotype.Component;

/** Compatibility facade for knowledge index initialization, version preparation, and alias management. */
@Component
public class KnowledgeIndexAdmin {

    private final KnowledgeIndexInitializer indexInitializer;
    private final KnowledgeIndexVersionManager versionManager;
    private final KnowledgeIndexAliasManager aliasManager;
    private final KnowledgeIndexNaming indexNaming;

    public KnowledgeIndexAdmin(
            KnowledgeIndexInitializer indexInitializer,
            KnowledgeIndexVersionManager versionManager,
            KnowledgeIndexAliasManager aliasManager,
            KnowledgeIndexNaming indexNaming) {
        this.indexInitializer = indexInitializer;
        this.versionManager = versionManager;
        this.aliasManager = aliasManager;
        this.indexNaming = indexNaming;
    }

    public void ensureVectorMapping(int actualDimensions) {
        indexInitializer.ensureVectorMapping(actualDimensions);
    }

    public boolean indexExists() {
        return versionManager.indexExists();
    }

    public boolean aliasEnabled() {
        return indexNaming.aliasEnabled();
    }

    public VersionPreparation prepareVersion(String rawVersion, boolean reindex) {
        KnowledgeIndexVersionManager.PreparedVersion preparedVersion = versionManager.prepare(rawVersion, reindex);
        return new VersionPreparation(
                preparedVersion.version(),
                preparedVersion.index(),
                preparedVersion.created(),
                preparedVersion.total(),
                preparedVersion.createdDocuments(),
                preparedVersion.updatedDocuments());
    }

    public AliasStatus aliasStatus() {
        return aliasStatus(aliasManager.status());
    }

    public AliasStatus activateVersion(String rawVersion) {
        indexNaming.requireAlias();
        String target = indexNaming.versionedIndex(indexNaming.normalizeVersion(rawVersion));
        return aliasStatus(aliasManager.activateVersion(target));
    }

    public AliasStatus rollback(String rawVersion) {
        return activateVersion(rawVersion);
    }

    String normalizeVersion(String rawVersion) {
        return indexNaming.normalizeVersion(rawVersion);
    }

    String versionedIndex(String version) {
        return indexNaming.versionedIndex(version);
    }

    private AliasStatus aliasStatus(KnowledgeIndexAliasManager.AliasSnapshot snapshot) {
        return new AliasStatus(snapshot.alias(), snapshot.activeIndex(), snapshot.backingIndices());
    }

    public record VersionPreparation(
            String version, String index, boolean created, long total, long createdDocuments, long updatedDocuments) {}

    public record AliasStatus(String alias, String activeIndex, List<String> backingIndices) {}
}
