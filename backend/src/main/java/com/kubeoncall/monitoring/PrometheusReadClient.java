package com.kubeoncall.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

/**
 * Small Prometheus HTTP API client used by the Console monitoring read model.
 *
 * <p>The caller supplies server-owned PromQL templates. This class deliberately does not expose a
 * generic web endpoint or accept browser-provided PromQL.
 */
@Component
public class PrometheusReadClient {

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ToolHttpClient httpClient;
    private final KubeOnCallProperties properties;

    public PrometheusReadClient(ToolHttpClient httpClient, KubeOnCallProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public List<InstantSample> instant(String query) {
        Map<String, Object> response = request("instantQuery", query, null, null, null);
        return result(response).stream().map(this::instantSample).toList();
    }

    public List<RangeSeries> range(String query, Instant start, Instant end, Duration step) {
        Map<String, Object> response = request("rangeQuery", query, start, end, step);
        return result(response).stream().map(this::rangeSeries).toList();
    }

    private Map<String, Object> request(String action, String query, Instant start, Instant end, Duration step) {
        KubeOnCallProperties.Endpoint config = properties.getIntegrations().getPrometheus();
        String endpoint = config.getEndpoint();
        Map<String, Object> result;
        if (endpoint != null && endpoint.contains("/api/tools/")) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("query", query);
            if (start != null && end != null) {
                parameters.put("start", start.getEpochSecond());
                parameters.put("end", end.getEpochSecond());
                parameters.put("step", Math.max(1, step == null ? 30 : step.toSeconds()));
            }
            result = httpClient.post(
                    endpoint,
                    Map.of("executor", "prometheus", "action", action, "parameters", parameters),
                    config.getTimeoutMillis(),
                    Map.of(),
                    Map.of("targetSystem", "prometheus"),
                    MAX_RESPONSE_BYTES);
        } else {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("query", query);
            String path = "/api/v1/query";
            if (start != null && end != null) {
                path = "/api/v1/query_range";
                parameters.put("start", start.getEpochSecond());
                parameters.put("end", end.getEpochSecond());
                parameters.put("step", Math.max(1, step == null ? 30 : step.toSeconds()));
            }
            result = httpClient.get(
                    trimTrailingSlash(endpoint) + path,
                    parameters,
                    config.getTimeoutMillis(),
                    Map.of(),
                    Map.of("targetSystem", "prometheus"),
                    MAX_RESPONSE_BYTES);
        }
        return unwrap(result);
    }

    private Map<String, Object> unwrap(Map<String, Object> wrapper) {
        Object current = wrapper;
        for (int depth = 0; depth < 4; depth++) {
            if (!(current instanceof Map<?, ?> map)) {
                break;
            }
            Object data = map.get("data");
            if ("success".equalsIgnoreCase(String.valueOf(map.get("status"))) && data instanceof Map<?, ?>) {
                return stringMap(map);
            }
            if ("failed".equalsIgnoreCase(String.valueOf(map.get("status")))) {
                Object errorMessage = map.get("errorMessage");
                throw new MonitoringDataSourceException(
                        errorMessage == null ? "Prometheus request failed" : String.valueOf(errorMessage));
            }
            current = map.get("response");
        }
        throw new MonitoringDataSourceException("Prometheus returned an invalid response");
    }

    private List<Map<String, Object>> result(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap) || !(dataMap.get("result") instanceof List<?> values)) {
            throw new MonitoringDataSourceException("Prometheus response is missing result data");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                result.add(stringMap(map));
            }
        }
        return List.copyOf(result);
    }

    private InstantSample instantSample(Map<String, Object> raw) {
        return new InstantSample(
                labels(raw.get("metric")), numberPair(raw.get("value")).value());
    }

    private RangeSeries rangeSeries(Map<String, Object> raw) {
        List<Point> points = new ArrayList<>();
        if (raw.get("values") instanceof List<?> values) {
            for (Object value : values) {
                NumberPair pair = numberPair(value);
                points.add(new Point(Instant.ofEpochSecond((long) pair.timestamp()), pair.value()));
            }
        }
        return new RangeSeries(labels(raw.get("metric")), List.copyOf(points));
    }

    private Map<String, String> labels(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                labels.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return Map.copyOf(labels);
    }

    private NumberPair numberPair(Object raw) {
        if (!(raw instanceof List<?> pair) || pair.size() < 2) {
            throw new MonitoringDataSourceException("Prometheus sample has an invalid value");
        }
        try {
            return new NumberPair(
                    Double.parseDouble(String.valueOf(pair.get(0))), Double.parseDouble(String.valueOf(pair.get(1))));
        } catch (NumberFormatException ex) {
            throw new MonitoringDataSourceException("Prometheus sample is not numeric", ex);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private static String trimTrailingSlash(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new MonitoringDataSourceException("Prometheus endpoint is not configured");
        }
        return endpoint.replaceAll("/+$", "");
    }

    public record InstantSample(Map<String, String> labels, double value) {}

    public record RangeSeries(Map<String, String> labels, List<Point> points) {}

    public record Point(Instant timestamp, double value) {}

    private record NumberPair(double timestamp, double value) {}
}
