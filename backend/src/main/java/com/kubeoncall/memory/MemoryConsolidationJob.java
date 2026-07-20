package com.kubeoncall.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Component
public class MemoryConsolidationJob {

    private static final String LOCK_KEY = "memory-consolidation:lock";

    private final MemoryConsolidationService consolidationService;
    private final KubeOnCallProperties properties;
    private final RedisLeaseLock leaseLock;
    private final KubeOnCallMetricsService metricsService;

    public MemoryConsolidationJob(
            MemoryConsolidationService consolidationService,
            KubeOnCallProperties properties,
            RedisLeaseLock leaseLock) {
        this(consolidationService, properties, leaseLock, null);
    }

    @Autowired
    public MemoryConsolidationJob(
            MemoryConsolidationService consolidationService,
            KubeOnCallProperties properties,
            RedisLeaseLock leaseLock,
            KubeOnCallMetricsService metricsService) {
        this.consolidationService = consolidationService;
        this.properties = properties;
        this.leaseLock = leaseLock;
        this.metricsService = metricsService;
    }

    @Scheduled(cron = "${kubeoncall.memory.consolidation-cron:0 0 3 * * *}")
    public void consolidate() {
        if (!properties.getMemory().isConsolidationEnabled()) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(Math.max(60, properties.getMemory().getConsolidationLockTtlSeconds()));
        if (!leaseLock.tryAcquire(LOCK_KEY, owner, ttl)) {
            recordLock("contended");
            return;
        }
        recordLock("acquired");
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "memory-consolidation-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
        long renewEverySeconds = Math.max(10L, ttl.toSeconds() / 3L);
        ScheduledFuture<?> renewal = renewer.scheduleAtFixedRate(
                () -> recordLock(leaseLock.renew(LOCK_KEY, owner, ttl) ? "renewed" : "renew_failed"),
                renewEverySeconds,
                renewEverySeconds,
                TimeUnit.SECONDS);
        try {
            consolidationService.consolidate(
                    Instant.now(), Math.max(1, properties.getMemory().getConsolidationScanLimit()), false);
        } finally {
            renewal.cancel(true);
            renewer.shutdownNow();
            recordLock(leaseLock.release(LOCK_KEY, owner) ? "released" : "release_failed");
        }
    }

    private void recordLock(String outcome) {
        if (metricsService != null) {
            metricsService.recordMemory("consolidation_lock", outcome, 1);
        }
    }
}
