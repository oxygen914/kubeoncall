package com.kubeoncall.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;
import com.kubeoncall.storage.StoredDocumentReference;

/**
 * Reconciles MinIO import sources that were uploaded before their MySQL transaction began but
 * survived a process crash. A grace period prevents a slow in-flight submission from being treated
 * as an orphan; durable source and error-report references are always retained.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeImportOrphanCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeImportOrphanCleanupJob.class);
    private static final String IMPORT_PREFIX = "knowledge/imports/";

    private final ObjectProvider<KnowledgeImportRepository> importRepositoryProvider;
    private final ObjectProvider<KnowledgeObjectStorageService> storageProvider;
    private final KubeOnCallProperties properties;

    public KnowledgeImportOrphanCleanupJob(
            ObjectProvider<KnowledgeImportRepository> importRepositoryProvider,
            ObjectProvider<KnowledgeObjectStorageService> storageProvider,
            KubeOnCallProperties properties) {
        this.importRepositoryProvider = importRepositoryProvider;
        this.storageProvider = storageProvider;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.knowledge.orphan-cleanup-interval-millis:21600000}")
    public void scheduledCleanup() {
        CleanupResult result = cleanup(Instant.now());
        if (result.deleted() > 0 || result.failed()) {
            log.info(
                    "Knowledge import orphan cleanup: scanned={}, retained={}, deleted={}, failed={}",
                    result.scanned(),
                    result.retained(),
                    result.deleted(),
                    result.failed());
        }
    }

    CleanupResult cleanup(Instant now) {
        KnowledgeImportRepository imports = importRepositoryProvider.getIfAvailable();
        KnowledgeObjectStorageService storage = storageProvider.getIfAvailable();
        String bucket = properties.getStorage().getMinio().getBucket();
        if (imports == null || storage == null || bucket == null || bucket.isBlank()) {
            return new CleanupResult(0, 0, 0, false);
        }
        try {
            Instant olderThan = now.minus(
                    Duration.ofSeconds(Math.max(60, properties.getKnowledge().getOrphanGraceSeconds())));
            Set<String> referenced = imports.referencedObjectKeys(bucket);
            int scanned = 0;
            int retained = 0;
            int deleted = 0;
            for (String objectKey : storage.listObjectKeysOlderThan(IMPORT_PREFIX, olderThan)) {
                scanned++;
                if (referenced.contains(objectKey)) {
                    retained++;
                    continue;
                }
                if (storage.remove(new StoredDocumentReference(objectKey, bucket, true, "orphan import source"))) {
                    deleted++;
                }
            }
            return new CleanupResult(scanned, retained, deleted, false);
        } catch (RuntimeException ex) {
            log.warn(
                    "Knowledge import orphan cleanup failed: errorType={}",
                    ex.getClass().getSimpleName());
            return new CleanupResult(0, 0, 0, true);
        }
    }

    record CleanupResult(int scanned, int retained, int deleted, boolean failed) {}
}
