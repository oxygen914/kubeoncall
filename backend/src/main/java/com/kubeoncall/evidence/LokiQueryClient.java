package com.kubeoncall.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.tool.http.ToolHttpClient;

/**
 * Read-only, template-only Loki query_range adapter.
 *
 * <p>Callers provide typed label values, never raw LogQL. This class applies the allowlist, time,
 * line-count, response-size and redaction boundaries before returning evidence.
 */
@Component
public class LokiQueryClient {

    private final ToolHttpClient httpClient;
    private final KubeOnCallProperties properties;
    private final EvidenceScopePolicy scopePolicy;
    private final KubeOnCallMetricsService metrics;
    private final SensitiveDataRedactor redactor = SensitiveDataRedactor.STANDARD;

    public LokiQueryClient(
            ToolHttpClient httpClient,
            KubeOnCallProperties properties,
            EvidenceScopePolicy scopePolicy,
            KubeOnCallMetricsService metrics) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.scopePolicy = scopePolicy;
        this.metrics = metrics;
    }

    public Result queryRange(Request request) {
        long startedAt = System.currentTimeMillis();
        if (!properties.getAiOperations().isEvidenceLokiEnabled()) {
            return result(EvidenceCollectionStatus.UNAVAILABLE, List.of(), "FEATURE_DISABLED", false, startedAt);
        }
        var rejection = scopePolicy.rejection(request.scope());
        if (rejection.isPresent()) {
            return result(EvidenceCollectionStatus.FORBIDDEN, List.of(), rejection.get(), false, startedAt);
        }
        int maxWindow = Math.max(1, properties.getAiOperations().getLokiMaxWindowMinutes());
        long requestedMinutes = Math.max(
                1,
                Duration.between(request.scope().start(), request.scope().end()).toMinutes());
        if (requestedMinutes > maxWindow) {
            return result(EvidenceCollectionStatus.FORBIDDEN, List.of(), "TIME_WINDOW_EXCEEDS_LIMIT", false, startedAt);
        }
        int limit = Math.max(
                1,
                Math.min(
                        request.limit() <= 0 ? properties.getAiOperations().getLokiMaxLines() : request.limit(),
                        properties.getAiOperations().getLokiMaxLines()));
        String query = buildQuery(request);
        String endpoint = endpoint(properties.getIntegrations().getLoki().getEndpoint());
        Map<String, Object> response = httpClient.get(
                endpoint,
                Map.of(
                        "query",
                        query,
                        "start",
                        request.scope().start().toString(),
                        "end",
                        request.scope().end().toString(),
                        "limit",
                        limit,
                        "direction",
                        "backward"),
                properties.getIntegrations().getLoki().getTimeoutMillis(),
                Map.of(),
                Map.of("source", "loki", "operation", "query_range"),
                Math.max(1024, properties.getAiOperations().getLokiMaxResponseBytes()));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return result(
                    EvidenceCollectionStatus.UNAVAILABLE,
                    List.of(),
                    String.valueOf(response.getOrDefault("errorType", "LOKI_UNAVAILABLE")),
                    false,
                    startedAt);
        }
        List<LogEntry> entries = parseEntries(response.get("response"), limit);
        return result(
                entries.isEmpty() ? EvidenceCollectionStatus.EMPTY : EvidenceCollectionStatus.SUCCEEDED,
                entries,
                "",
                entries.size() >= limit,
                startedAt);
    }

    private String buildQuery(Request request) {
        List<String> labels = new ArrayList<>();
        addLabel(labels, "cluster", request.scope().cluster());
        addLabel(labels, "namespace", request.scope().namespace());
        addLabel(labels, "workload", request.workload());
        addLabel(labels, "pod", request.pod());
        addLabel(labels, "container", request.container());
        String selector = "{" + String.join(",", labels) + "}";
        if (request.keyword() == null || request.keyword().isBlank()) {
            return selector;
        }
        return selector + " |= \"" + escapeString(request.keyword()) + "\"";
    }

    private List<LogEntry> parseEntries(Object response, int limit) {
        Map<String, Object> root = map(response);
        Map<String, Object> data = map(root.get("data"));
        Object result = data.get("result");
        if (!(result instanceof List<?> streams)) {
            return List.of();
        }
        List<LogEntry> entries = new ArrayList<>();
        for (Object streamCandidate : streams) {
            Map<String, Object> stream = map(streamCandidate);
            Map<String, Object> labels = map(stream.get("stream"));
            Object values = stream.get("values");
            if (!(values instanceof List<?> rows)) {
                continue;
            }
            for (Object rowCandidate : rows) {
                if (!(rowCandidate instanceof List<?> row) || row.size() < 2) {
                    continue;
                }
                Instant observedAt = lokiInstant(row.get(0));
                String line = redactor.redactText(String.valueOf(row.get(1)));
                entries.add(new LogEntry(observedAt, line, labels));
                if (entries.size() >= limit) {
                    return List.copyOf(entries);
                }
            }
        }
        return List.copyOf(entries);
    }

    private Result result(
            EvidenceCollectionStatus status,
            List<LogEntry> entries,
            String errorType,
            boolean truncated,
            long startedAt) {
        long latencyMs = Math.max(0, System.currentTimeMillis() - startedAt);
        metrics.recordEvidenceCollection("loki", status.name(), latencyMs);
        return new Result(status, entries, errorType, latencyMs, truncated);
    }

    private static Instant lokiInstant(Object value) {
        try {
            long nanos = Long.parseLong(String.valueOf(value));
            return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void addLabel(List<String> labels, String name, String value) {
        if (value != null && !value.isBlank()) {
            labels.add(name + "=\"" + escapeString(value) + "\"");
        }
    }

    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String endpoint(String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        String normalized = configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        return normalized.toLowerCase(Locale.ROOT).endsWith("/loki/api/v1/query_range")
                ? normalized
                : normalized + "/loki/api/v1/query_range";
    }

    public record Request(
            EvidenceCollectionScope scope, String workload, String pod, String container, String keyword, int limit) {

        public Request {
            if (scope == null) {
                throw new IllegalArgumentException("Loki evidence scope is required");
            }
            workload = safe(workload);
            pod = safe(pod);
            container = safe(container);
            keyword = safe(keyword);
        }
    }

    public record LogEntry(Instant observedAt, String line, Map<String, Object> labels) {

        public LogEntry {
            observedAt = observedAt == null ? Instant.now() : observedAt;
            line = safe(line);
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
    }

    public record Result(
            EvidenceCollectionStatus status,
            List<LogEntry> entries,
            String errorType,
            long latencyMs,
            boolean truncated) {

        public Result {
            status = status == null ? EvidenceCollectionStatus.FAILED : status;
            entries = entries == null ? List.of() : List.copyOf(entries);
            errorType = safe(errorType);
            latencyMs = Math.max(0, latencyMs);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
