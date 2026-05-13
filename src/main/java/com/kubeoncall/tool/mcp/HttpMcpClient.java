package com.kubeoncall.tool.mcp;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
                properties.getMcp().getEndpoint(),
                request,
                properties.getMcp().getTimeoutMillis(),
                metadata
        );
    }
}
