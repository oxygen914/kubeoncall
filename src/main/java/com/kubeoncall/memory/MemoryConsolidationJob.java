package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class MemoryConsolidationJob {

    private static final String LOCK_KEY = "memory-consolidation:lock";

    private final MemoryConsolidationService consolidationService;
    private final KubeOnCallProperties properties;
    private final RedisLeaseLock leaseLock;

    @Autowired
    public MemoryConsolidationJob(MemoryConsolidationService consolidationService,
                                  KubeOnCallProperties properties,
                                  RedisLeaseLock leaseLock) {
        this.consolidationService = consolidationService;
        this.properties = properties;
        this.leaseLock = leaseLock;
    }

    public MemoryConsolidationJob(MemoryConsolidationService consolidationService,
                                  KubeOnCallProperties properties) {
        this(consolidationService, properties, null);
    }

    @Scheduled(cron = "${kubeoncall.memory.consolidation-cron:0 0 3 * * *}")
    public void consolidate() {
        if (!properties.getMemory().isConsolidationEnabled()) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(Math.max(
                60, properties.getMemory().getConsolidationLockTtlSeconds()));
        if (leaseLock != null && !leaseLock.tryAcquire(LOCK_KEY, owner, ttl)) {
            return;
        }
        try {
            consolidationService.consolidate(
                    Instant.now(),
                    Math.max(1, properties.getMemory().getConsolidationScanLimit()),
                    false);
        } finally {
            if (leaseLock != null) {
                leaseLock.release(LOCK_KEY, owner);
            }
        }
    }
}
