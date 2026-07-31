package com.kubeoncall.task.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kubeoncall.task.AsyncTaskRepository;

/** Wires the generic worker with all discovered task handlers when MySQL facts are enabled. */
@Configuration
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AsyncTaskWorkerConfiguration {

    @Bean
    public AsyncTaskHandlerRegistry asyncTaskHandlerRegistry(List<AsyncTaskHandler> handlers) {
        return new AsyncTaskHandlerRegistry(handlers);
    }

    @Bean
    public AsyncTaskWorker asyncTaskWorker(
            AsyncTaskRepository repository,
            AsyncTaskHandlerRegistry registry,
            ObjectProvider<Clock> clockProvider,
            @Value("${kubeoncall.worker.task.owner-prefix:kubeoncall}") String ownerPrefix,
            @Value("${kubeoncall.worker.task.lease-seconds:300}") long leaseSeconds,
            @Value("${kubeoncall.worker.task.initial-backoff-seconds:2}") long initialBackoffSeconds,
            @Value("${kubeoncall.worker.task.max-backoff-seconds:300}") long maxBackoffSeconds,
            List<AsyncTaskLifecycleListener> lifecycleListeners) {
        long safeInitialBackoffSeconds = Math.max(1, initialBackoffSeconds);
        return new AsyncTaskWorker(
                repository,
                registry,
                clockProvider.getIfAvailable(Clock::systemUTC),
                ownerToken(ownerPrefix),
                Duration.ofSeconds(Math.max(1, leaseSeconds)),
                Duration.ofSeconds(safeInitialBackoffSeconds),
                Duration.ofSeconds(Math.max(safeInitialBackoffSeconds, maxBackoffSeconds)),
                Set.of(),
                lifecycleListeners);
    }

    /**
     * An empty type set means "claim any type", which is needed for unknown-type dead-lettering.
     * The helper remains explicit to document that handlers are selected by the registry after
     * claim rather than filtering unknown tasks out forever.
     */
    private static String ownerToken(String prefix) {
        String safePrefix = prefix == null || prefix.isBlank() ? "kubeoncall" : prefix.trim();
        String token = safePrefix + "-" + UUID.randomUUID();
        return token.length() <= 128 ? token : token.substring(0, 128);
    }
}
