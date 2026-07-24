package com.kubeoncall.service;

import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

@Service
public class KubeOnCallMetricsService {

    private final MeterRegistry meterRegistry;

    public KubeOnCallMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry provided = Objects.requireNonNull(meterRegistryProvider, "meterRegistryProvider")
                .getIfAvailable();
        this.meterRegistry = provided == null ? new CompositeMeterRegistry() : provided;
    }

    public void recordGraphExecution(String requestType, String status, boolean degraded, boolean approvalRequired) {
        increment(
                "kubeoncall.graph.executions",
                "request_type",
                safe(requestType),
                "status",
                safe(status),
                "degraded",
                String.valueOf(degraded),
                "approval_required",
                String.valueOf(approvalRequired));
    }

    public void recordAlarmExecution(String status, boolean degraded, boolean autoHandled) {
        increment(
                "kubeoncall.alarm.executions",
                "status",
                safe(status),
                "degraded",
                String.valueOf(degraded),
                "auto_handled",
                String.valueOf(autoHandled));
    }

    public void recordAlarmSilenceApproval(String outcome) {
        increment("kubeoncall.alarm.silence_approvals", "outcome", safe(outcome));
    }

    public void recordAlarmAcknowledgement(String outcome) {
        increment("kubeoncall.alarm.acknowledgements", "outcome", safe(outcome));
    }

    public void recordAlarmInbox(String outcome) {
        increment("kubeoncall.alarm.inbox", "outcome", safe(outcome));
    }

    public void recordAlarmRecovery(String outcome, String severity) {
        increment("kubeoncall.alarm.recoveries", "outcome", safe(outcome), "severity", safe(severity));
    }

    public void recordAlarmQuality(String outcome) {
        increment("kubeoncall.alarm.quality", "outcome", safe(outcome));
    }

    public void recordAlarmDuration(String phase, String severity, long durationMs) {
        recordAmount(
                "kubeoncall.alarm.lifecycle_duration_ms",
                Math.max(0, durationMs),
                "phase",
                safe(phase),
                "severity",
                safe(severity));
    }

    public void recordChangeEvent(String outcome, String source) {
        increment("kubeoncall.change_events", "outcome", safe(outcome), "source", safe(source));
    }

    public void recordRagRetrieval(
            String method, String vectorSource, boolean vectorFallback, long resultCount, long latencyMs) {
        increment(
                "kubeoncall.rag.retrievals",
                "method",
                safe(method),
                "vector_source",
                safe(vectorSource),
                "vector_fallback",
                String.valueOf(vectorFallback));
        recordAmount("kubeoncall.rag.result_count", Math.max(0, resultCount), "method", safe(method));
        recordAmount("kubeoncall.rag.latency_ms", Math.max(0, latencyMs), "method", safe(method));
        if (resultCount <= 0) {
            increment("kubeoncall.rag.empty_results", "method", safe(method));
        }
    }

    public void recordRagRerank(boolean crossEncoderEnabled, boolean applied, boolean fallback) {
        increment(
                "kubeoncall.rag.reranks",
                "cross_encoder_enabled",
                String.valueOf(crossEncoderEnabled),
                "applied",
                String.valueOf(applied),
                "fallback",
                String.valueOf(fallback));
    }

    public void recordKnowledge(String operation, String outcome, long count) {
        increment("kubeoncall.knowledge.operations", "operation", safe(operation), "outcome", safe(outcome));
        recordAmount(
                "kubeoncall.knowledge.operation_count",
                Math.max(0, count),
                "operation",
                safe(operation),
                "outcome",
                safe(outcome));
    }

    public void recordMemory(String operation, String outcome, long count) {
        increment("kubeoncall.memory.operations", "operation", safe(operation), "outcome", safe(outcome));
        recordAmount(
                "kubeoncall.memory.operation_count",
                Math.max(0, count),
                "operation",
                safe(operation),
                "outcome",
                safe(outcome));
    }

    public void recordMemoryInjection(String outcome, long entryCount) {
        increment("kubeoncall.memory.injections", "outcome", safe(outcome));
        recordAmount("kubeoncall.memory.injected_count", Math.max(0, entryCount), "outcome", safe(outcome));
    }

    public void recordMemoryExtraction(String mode, String outcome, long extractedCount) {
        increment("kubeoncall.memory.extractions", "mode", safe(mode), "outcome", safe(outcome));
        recordAmount(
                "kubeoncall.memory.extracted_count",
                Math.max(0, extractedCount),
                "mode",
                safe(mode),
                "outcome",
                safe(outcome));
    }

    public void recordSkillActivation(boolean active, long skillCount) {
        increment("kubeoncall.skill.activations", "active", String.valueOf(active));
        recordAmount("kubeoncall.skill.activated_count", Math.max(0, skillCount), "active", String.valueOf(active));
    }

    public void recordSkillGovernance(String operation, String outcome) {
        increment("kubeoncall.skill.governance", "operation", safe(operation), "outcome", safe(outcome));
    }

    /** Records a legacy Console API response with a mapping-pattern endpoint tag, never raw IDs. */
    public void recordLegacyApiRequest(String endpoint, String method, int status) {
        increment(
                "kubeoncall.legacy_api.requests",
                "endpoint",
                safe(endpoint),
                "method",
                safe(method),
                "status",
                statusFamily(status));
    }

    /** Records a bounded-cardinality dependency call outcome and end-to-end client latency. */
    public void recordDependency(String dependency, String operation, String outcome, long latencyMs) {
        increment(
                "kubeoncall.dependency.requests",
                "dependency",
                safe(dependency),
                "operation",
                safe(operation),
                "outcome",
                safe(outcome));
        recordAmount(
                "kubeoncall.dependency.latency_ms",
                Math.max(0, latencyMs),
                "dependency",
                safe(dependency),
                "operation",
                safe(operation),
                "outcome",
                safe(outcome));
    }

    /** Records lifecycle transitions of the process-local dependency circuit breaker. */
    public void recordDependencyCircuit(String dependency, String event) {
        increment("kubeoncall.dependency.circuit", "dependency", safe(dependency), "event", safe(event));
    }

    /** Records bounded worker lease transitions: claimed, renewed, lost or fencing-rejected. */
    public void recordWorkerLease(String worker, String event) {
        increment("kubeoncall.worker.lease", "worker", safe(worker), "event", safe(event));
    }

    /** Records whether a durable worker received a correlation id from its submitting request. */
    public void recordRequestCorrelation(String boundary, boolean present) {
        increment(
                "kubeoncall.request_correlation",
                "boundary",
                safe(boundary),
                "outcome",
                present ? "propagated" : "missing");
    }

    /**
     * Records that a Redis compatibility-projection write was skipped because the domain's
     * {@code legacyWriteDisabled} soft switch is on (WBS-11 GAP-11-02). The MySQL fact is unaffected;
     * this counter lets operators confirm compatibility writes have actually stopped.
     */
    public void recordLegacyWriteSkipped(String domain) {
        increment("kubeoncall.migration.legacy_write_skipped", "domain", safe(domain));
    }

    private void increment(String name, String... tags) {
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }

    private void recordAmount(String name, double amount, String... tags) {
        DistributionSummary.builder(name).tags(tags).register(meterRegistry).record(amount);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String statusFamily(int status) {
        if (status >= 500) {
            return "5xx";
        }
        if (status >= 400) {
            return "4xx";
        }
        if (status >= 300) {
            return "3xx";
        }
        return "2xx";
    }
}
