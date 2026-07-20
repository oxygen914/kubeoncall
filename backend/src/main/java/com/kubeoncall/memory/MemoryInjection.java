package com.kubeoncall.memory;

import java.util.List;

public record MemoryInjection(List<MemoryEntry> entries, String prompt, String warning) {
    public MemoryInjection {
        entries = entries == null ? List.of() : List.copyOf(entries);
        prompt = prompt == null ? "" : prompt;
        warning = warning == null ? "" : warning;
    }

    public static MemoryInjection empty() {
        return new MemoryInjection(List.of(), "", "");
    }
}
