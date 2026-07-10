package com.kubeoncall.rag.runbook;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunbookCatalogTest {

    @Test
    void shouldLoadProductionRunbookAssetsWithRequiredMetadata() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setRunbookLocation("classpath*:runbooks/*.md");

        List<RunbookAsset> assets = new RunbookCatalog(properties).load();

        assertEquals(8, assets.size());
        Set<String> ids = assets.stream().map(RunbookAsset::runbookId).collect(Collectors.toSet());
        assertEquals(Set.of(
                "runbook-host-cpu-high",
                "runbook-host-memory-pressure",
                "runbook-host-disk-usage",
                "runbook-host-inode-usage",
                "runbook-node-notready",
                "runbook-pod-crashloop",
                "runbook-pod-oom",
                "runbook-deployment-unavailable"), ids);
        assertTrue(assets.stream().allMatch(asset -> asset.content().length() >= 500));
        assertTrue(assets.stream().allMatch(asset -> "runbook".equals(asset.metadata().get("document_type"))));
        assertTrue(assets.stream().allMatch(asset -> "v1".equals(asset.metadata().get("dataset_version"))));
        assertTrue(assets.stream().allMatch(asset -> asset.metadata().containsKey("owner")));
    }
}
