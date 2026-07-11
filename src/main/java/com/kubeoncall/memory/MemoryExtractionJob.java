package com.kubeoncall.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(
        name = "kubeoncall.memory.extraction-worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MemoryExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionJob.class);

    private final MemoryExtractionQueue queue;
    private final MemoryService memoryService;

    public MemoryExtractionJob(MemoryExtractionQueue queue, MemoryService memoryService) {
        this.queue = queue;
        this.memoryService = memoryService;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.memory.extraction-poll-interval-millis:5000}")
    public void processNext() {
        queue.claim().ifPresent(claimed -> {
            try {
                MemoryExtractionTask task = claimed.task();
                Instant now = Instant.now();
                memoryService.remember(new MemoryEntry(
                        null, task.memoryType(), task.scope(), task.subject(), task.content(), task.service(),
                        task.resource(), task.fingerprint(), task.createdAt(), now, task.metadata()));
                queue.acknowledge(claimed);
            } catch (RuntimeException ex) {
                queue.fail(claimed, ex);
                log.warn("Memory extraction task {} failed: {}", claimed.task().id(), ex.getMessage());
            }
        });
    }
}
