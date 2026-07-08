package com.kubeoncall.alarm.domain;

import java.util.Locale;

/**
 * The kind of infrastructure object an alarm is about. Used for policy matching, runbook filtering,
 * and selecting a workflow template.
 */
public enum AlarmResourceType {
    NODE,
    POD,
    DEPLOYMENT,
    STATEFULSET,
    NAMESPACE,
    CLUSTER,
    SERVICE,
    HOST,
    WORKLOAD;

    /** Lenient parse that tolerates the casing/input an upstream webhook might send. */
    public static AlarmResourceType fromRaw(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (AlarmResourceType t : values()) {
            if (t.name().equals(normalized)) {
                return t;
            }
        }
        // Common aliases
        return switch (normalized) {
            case "NODES" -> NODE;
            case "PODS" -> POD;
            case "DEPLOYMENTS" -> DEPLOYMENT;
            case "STATEFULSETS" -> STATEFULSET;
            case "NAMESPACES" -> NAMESPACE;
            case "CLUSTERS" -> CLUSTER;
            case "SERVICES" -> SERVICE;
            case "HOSTS", "INSTANCE", "MACHINE" -> HOST;
            case "DAEMONSET", "REPLICASET", "HPA" -> WORKLOAD;
            default -> null;
        };
    }
}
