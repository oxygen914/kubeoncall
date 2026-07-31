package com.kubeoncall.skill;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-request LRU buffer for activated Skill bodies with one-shot drain semantics. */
public final class SkillContextBuffer {

    private final int maxEntries;
    private final Map<String, String> entries = new LinkedHashMap<>();

    public SkillContextBuffer(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public synchronized void push(String name, String body) {
        String key = name == null ? "" : name.trim();
        if (key.isBlank() || body == null || body.isBlank()) {
            return;
        }
        entries.remove(key);
        entries.put(key, body.trim());
        while (entries.size() > maxEntries) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    public synchronized String drain() {
        StringBuilder result = new StringBuilder();
        entries.forEach((name, body) -> {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append("## Loaded Skill: ").append(name).append('\n').append(body);
        });
        entries.clear();
        return result.toString();
    }

    public synchronized int size() {
        return entries.size();
    }
}
