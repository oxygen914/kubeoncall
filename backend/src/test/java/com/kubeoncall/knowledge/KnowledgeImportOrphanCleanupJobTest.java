package com.kubeoncall.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.knowledge.mysql.KnowledgeImportRepository;
import com.kubeoncall.storage.KnowledgeObjectStorageService;

class KnowledgeImportOrphanCleanupJobTest {

    @Test
    void deletesOnlyUnreferencedObjectsPastTheGracePeriod() {
        KnowledgeImportRepository imports = mock(KnowledgeImportRepository.class);
        KnowledgeObjectStorageService storage = mock(KnowledgeObjectStorageService.class);
        when(imports.referencedObjectKeys("knowledge")).thenReturn(Set.of("knowledge/imports/imp_1/source.jsonl"));
        when(storage.listObjectKeysOlderThan(eq("knowledge/imports/"), any()))
                .thenReturn(
                        List.of("knowledge/imports/imp_1/source.jsonl", "knowledge/imports/imp_orphan/source.jsonl"));
        when(storage.remove(any())).thenReturn(true);

        KnowledgeImportOrphanCleanupJob job =
                new KnowledgeImportOrphanCleanupJob(provider(imports), provider(storage), properties());

        KnowledgeImportOrphanCleanupJob.CleanupResult result = job.cleanup(Instant.parse("2026-07-22T00:00:00Z"));

        assertThat(result).isEqualTo(new KnowledgeImportOrphanCleanupJob.CleanupResult(2, 1, 1, false));
        verify(storage)
                .remove(org.mockito.ArgumentMatchers.argThat(
                        reference -> "knowledge/imports/imp_orphan/source.jsonl".equals(reference.objectKey())
                                && "knowledge".equals(reference.bucket())));
    }

    @Test
    void treatsStorageFailureAsAFailedRunWithoutDeletingAnything() {
        KnowledgeImportRepository imports = mock(KnowledgeImportRepository.class);
        KnowledgeObjectStorageService storage = mock(KnowledgeObjectStorageService.class);
        when(imports.referencedObjectKeys("knowledge")).thenThrow(new IllegalStateException("MinIO unavailable"));
        KnowledgeImportOrphanCleanupJob job =
                new KnowledgeImportOrphanCleanupJob(provider(imports), provider(storage), properties());

        assertThat(job.cleanup(Instant.parse("2026-07-22T00:00:00Z")).failed()).isTrue();
    }

    private static KubeOnCallProperties properties() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getStorage().getMinio().setBucket("knowledge");
        properties.getKnowledge().setOrphanGraceSeconds(60);
        return properties;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
