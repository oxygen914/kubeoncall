package com.kubeoncall.audit.read;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic field-level diff between the redacted before and after audit snapshots. */
public record AuditDiff(Map<String, Object> added, Map<String, Object> removed, Map<String, ValueChange> changed) {

    public AuditDiff {
        added = immutableCopy(added);
        removed = immutableCopy(removed);
        changed = immutableCopy(changed);
    }

    public static AuditDiff between(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> safeBefore = before == null ? Map.of() : before;
        Map<String, Object> safeAfter = after == null ? Map.of() : after;
        Map<String, Object> added = new LinkedHashMap<>();
        Map<String, Object> removed = new LinkedHashMap<>();
        Map<String, ValueChange> changed = new LinkedHashMap<>();
        compare("", safeBefore, safeAfter, added, removed, changed);
        return new AuditDiff(added, removed, changed);
    }

    @SuppressWarnings("unchecked")
    private static void compare(
            String prefix,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> added,
            Map<String, Object> removed,
            Map<String, ValueChange> changed) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            boolean hadBefore = before.containsKey(key);
            boolean hasAfter = after.containsKey(key);
            if (!hadBefore) {
                added.put(path, after.get(key));
                continue;
            }
            if (!hasAfter) {
                removed.put(path, before.get(key));
                continue;
            }
            Object beforeValue = before.get(key);
            Object afterValue = after.get(key);
            if (beforeValue instanceof Map<?, ?> beforeMap && afterValue instanceof Map<?, ?> afterMap) {
                compare(path, (Map<String, Object>) beforeMap, (Map<String, Object>) afterMap, added, removed, changed);
            } else if (!Objects.equals(beforeValue, afterValue)) {
                changed.put(path, new ValueChange(beforeValue, afterValue));
            }
        }
    }

    private static <T> Map<String, T> immutableCopy(Map<String, T> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record ValueChange(Object before, Object after) {}
}
