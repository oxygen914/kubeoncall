package com.kubeoncall.common.k8s;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a bounded Kubernetes resource reference from the current user request.
 *
 * <p>This parser is intentionally conservative. It only accepts Kubernetes-style DNS names and a
 * small allowlist of resource kinds used by the governed read-only adapter. Authenticated request
 * scope and server-side allowlists still take precedence over values extracted from user text.
 */
public final class KubernetesRequestTargetParser {

    private static final String RESOURCE_NAME = "[a-z0-9](?:[a-z0-9._-]{0,251}[a-z0-9])?";
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("(?iu)(?<![\\p{Alnum}_-])"
            + "(?<kind>pod|node|deployment|deploy|statefulset|sts|daemonset|ds|节点|部署|有状态集|守护进程)"
            + "(?![\\p{Alnum}_-])\\s*(?:[/：:]|名为|名称为)?\\s*"
            + "(?<name>"
            + RESOURCE_NAME
            + ")");
    private static final Pattern NAMESPACE_BEFORE_LABEL =
            Pattern.compile("(?iu)(?<name>" + RESOURCE_NAME + ")\\s*(?:namespace|命名空间)(?:中|内)?");
    private static final Pattern NAMESPACE_AFTER_LABEL =
            Pattern.compile("(?iu)(?:namespace|命名空间)\\s*(?:为|是|[:：/])?\\s*(?<name>" + RESOURCE_NAME + ")");
    private static final String CURRENT_USER_MARKER = "current user:";

    private KubernetesRequestTargetParser() {}

    public static Target parse(String request) {
        String currentRequest = currentUserRequest(request);
        Matcher resourceMatcher = RESOURCE_PATTERN.matcher(currentRequest);
        String kind = "";
        String name = "";
        if (resourceMatcher.find()) {
            kind = canonicalKind(resourceMatcher.group("kind"));
            name = resourceMatcher.group("name");
        }
        return new Target(namespace(currentRequest), kind, name);
    }

    private static String namespace(String request) {
        Matcher after = NAMESPACE_AFTER_LABEL.matcher(request);
        if (after.find()) {
            return after.group("name");
        }
        Matcher before = NAMESPACE_BEFORE_LABEL.matcher(request);
        return before.find() ? before.group("name") : "";
    }

    private static String currentUserRequest(String request) {
        if (request == null || request.isBlank()) {
            return "";
        }
        String lower = request.toLowerCase(Locale.ROOT);
        int marker = lower.lastIndexOf(CURRENT_USER_MARKER);
        if (marker < 0) {
            return request;
        }
        return request.substring(marker + CURRENT_USER_MARKER.length()).trim();
    }

    private static String canonicalKind(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "pod" -> "Pod";
            case "node", "节点" -> "Node";
            case "deployment", "deploy", "部署" -> "Deployment";
            case "statefulset", "sts", "有状态集" -> "StatefulSet";
            case "daemonset", "ds", "守护进程" -> "DaemonSet";
            default -> "";
        };
    }

    public record Target(String namespace, String resourceKind, String resourceName) {

        public Target {
            namespace = safe(namespace);
            resourceKind = safe(resourceKind);
            resourceName = safe(resourceName);
        }

        public boolean hasResource() {
            return !resourceKind.isBlank() && !resourceName.isBlank();
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
