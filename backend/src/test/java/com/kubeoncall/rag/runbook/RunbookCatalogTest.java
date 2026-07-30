package com.kubeoncall.rag.runbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;

class RunbookCatalogTest {

    @Test
    void shouldLoadProductionRunbookAssetsWithRequiredMetadata() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setRunbookLocation("classpath*:runbooks/*.md");

        List<RunbookAsset> assets = new RunbookCatalog(properties).load();

        assertEquals(17, assets.size());
        Set<String> ids = assets.stream().map(RunbookAsset::runbookId).collect(Collectors.toSet());
        assertEquals(
                Set.of(
                        "runbook-host-cpu-high",
                        "runbook-host-memory-pressure",
                        "runbook-host-disk-usage",
                        "runbook-host-inode-usage",
                        "runbook-host-file-descriptor-pressure",
                        "runbook-node-notready",
                        "runbook-pod-crashloop",
                        "runbook-pod-oom",
                        "runbook-deployment-unavailable",
                        "runbook-certificate-expiry",
                        "runbook-cluster-capacity",
                        "runbook-control-plane-apiserver",
                        "runbook-network-conntrack",
                        "runbook-service-slo",
                        "runbook-time-sync",
                        "runbook-kubeoncall-runtime",
                        "runbook-kubeoncall-sandbox"),
                ids);
        assertTrue(assets.stream().allMatch(asset -> asset.content().length() >= 500));
        assertTrue(assets.stream()
                .allMatch(asset -> "runbook".equals(asset.metadata().get("document_type"))));
        assertTrue(
                assets.stream().allMatch(asset -> "v1".equals(asset.metadata().get("dataset_version"))));
        assertTrue(assets.stream().allMatch(asset -> asset.metadata().containsKey("owner")));
    }
}
