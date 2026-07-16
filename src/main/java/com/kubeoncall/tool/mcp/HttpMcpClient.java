package com.kubeoncall.tool.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
@Primary
@ConditionalOnProperty(prefix = "kubeoncall.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpMcpClient implements McpClient {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public HttpMcpClient(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> call(String toolName, Map<String, Object> payload) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("serverName", properties.getMcp().getServerName());
        request.put("toolName", toolName);
        request.put("payload", payload == null ? Map.of() : payload);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetSystem", "mcp");
        metadata.put("tool", toolName);

        return toolHttpClient.post(
                properties.getMcp().getEndpoint(), request, properties.getMcp().getTimeoutMillis(), metadata);
    }

    @Override
    public List<Map<String, Object>> listTools() {
        if (!properties.getMcp().isDiscoveryEnabled()
                || properties.getMcp().getDiscoveryEndpoint() == null
                || properties.getMcp().getDiscoveryEndpoint().isBlank()) {
            return List.of();
        }
        Map<String, Object> response = toolHttpClient.post(
                properties.getMcp().getDiscoveryEndpoint(),
                Map.of("serverName", properties.getMcp().getServerName(), "method", "tools/list"),
                properties.getMcp().getTimeoutMillis(),
                Map.of("targetSystem", "mcp", "tool", "tools/list"));
        if (!"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            return List.of();
        }
        Object body = response.get("response");
        if (!(body instanceof Map<?, ?> map) || !(map.get("tools") instanceof List<?> tools)) {
            return List.of();
        }
        return tools.stream()
                .filter(Map.class::isInstance)
                .map(this::toStringMap)
                .toList();
    }

    private Map<String, Object> toStringMap(Object raw) {
        Map<?, ?> map = (Map<?, ?>) raw;
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((key, value) -> converted.put(String.valueOf(key), value));
        return Map.copyOf(converted);
    }
}
