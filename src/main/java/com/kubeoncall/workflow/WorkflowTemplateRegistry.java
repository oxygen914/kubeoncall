package com.kubeoncall.workflow;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Registry of workflow template names supported by the first alarm-governance iteration.
 *
 * <p>The factory still owns node wiring; this registry centralizes template normalization so policy
 * configuration can use stable names without coupling to Java conditionals.
 */
@Component
public class WorkflowTemplateRegistry {

    public static final String DEFAULT = "default";
    public static final String HOST_RESOURCE = "host-resource";
    public static final String K8S_POD = "k8s-pod";
    public static final String K8S_NODE = "k8s-node";
    public static final String WORKLOAD = "workload";
    public static final String CONTROL_PLANE = "control-plane";

    private static final Set<String> KNOWN = Set.of(
            DEFAULT,
            HOST_RESOURCE,
            K8S_POD,
            K8S_NODE,
            WORKLOAD,
            CONTROL_PLANE
    );

    public String normalize(String template) {
        if (template == null || template.isBlank()) {
            return DEFAULT;
        }
        String normalized = template.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(normalized) ? normalized : DEFAULT;
    }

    public boolean isKnown(String template) {
        return KNOWN.contains(normalize(template));
    }
}
