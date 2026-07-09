package com.kubeoncall.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class KubeOnCallMetricsService {

    private final MeterRegistry meterRegistry;

    public KubeOnCallMetricsService(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    public void recordGraphExecution(String requestType, String status, boolean degraded, boolean approvalRequired) {
        increment(
                "kubeoncall.graph.executions",
                "request_type", safe(requestType),
                "status", safe(status),
                "degraded", String.valueOf(degraded),
                "approval_required", String.valueOf(approvalRequired)
        );
    }

    public void recordAlarmExecution(String status, boolean degraded, boolean autoHandled) {
        increment(
                "kubeoncall.alarm.executions",
                "status", safe(status),
                "degraded", String.valueOf(degraded),
                "auto_handled", String.valueOf(autoHandled)
        );
    }

    public void recordRagRetrieval(String method, String vectorSource, boolean vectorFallback, long resultCount, long latencyMs) {
        increment(
                "kubeoncall.rag.retrievals",
                "method", safe(method),
                "vector_source", safe(vectorSource),
                "vector_fallback", String.valueOf(vectorFallback)
        );
        recordAmount("kubeoncall.rag.result_count", Math.max(0, resultCount), "method", safe(method));
        recordAmount("kubeoncall.rag.latency_ms", Math.max(0, latencyMs), "method", safe(method));
    }

    public void recordMemory(String operation, String outcome, long count) {
        increment(
                "kubeoncall.memory.operations",
                "operation", safe(operation),
                "outcome", safe(outcome)
        );
        recordAmount("kubeoncall.memory.operation_count", Math.max(0, count), "operation", safe(operation), "outcome", safe(outcome));
    }

    public void recordSkillActivation(boolean active, long skillCount) {
        increment(
                "kubeoncall.skill.activations",
                "active", String.valueOf(active)
        );
        recordAmount("kubeoncall.skill.activated_count", Math.max(0, skillCount), "active", String.valueOf(active));
    }

    private void increment(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    private void recordAmount(String name, double amount, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        DistributionSummary.builder(name)
                .tags(tags)
                .register(meterRegistry)
                .record(amount);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }
}
