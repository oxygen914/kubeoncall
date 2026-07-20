package com.kubeoncall.memory;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.concurrent.LeaseHeartbeat;

@Component
@ConditionalOnProperty(
        name = "kubeoncall.memory.extraction-worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MemoryExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionJob.class);

    private final MemoryExtractionQueue queue;
    private final MemoryService memoryService;
    private final MemoryExtractionPipeline extractionPipeline;

    public MemoryExtractionJob(
            MemoryExtractionQueue queue, MemoryService memoryService, MemoryExtractionPipeline extractionPipeline) {
        this.queue = queue;
        this.memoryService = memoryService;
        this.extractionPipeline = extractionPipeline;
    }

    @Scheduled(fixedDelayString = "${kubeoncall.memory.extraction-poll-interval-millis:5000}")
    public void processNext() {
        queue.claim().ifPresent(claimed -> {
            try (LeaseHeartbeat heartbeat = LeaseHeartbeat.start(
                    queue.processingLeaseTtl(), () -> queue.renew(claimed), "memory-extraction-lease-heartbeat")) {
                MemoryExtractionTask task = claimed.task();
                MemoryExtractionPipeline.ExtractionResult result = extractionPipeline.extract(task);
                List<MemoryEntry> persistedEntries = new ArrayList<>();
                result.entries().forEach(entry -> persistedEntries.add(memoryService.remember(entry)));
                if (!heartbeat.isValid() || !queue.renew(claimed)) {
                    log.warn("Memory extraction claim lost before acknowledgement: taskId={}", task.id());
                    return;
                }
                queue.acknowledge(claimed, result, persistedEntries);
            } catch (RuntimeException ex) {
                queue.fail(claimed, ex);
                log.warn(
                        "Memory extraction task failed: taskId={}, errorType={}",
                        claimed.task().id(),
                        ex.getClass().getSimpleName());
            }
        });
    }
}
