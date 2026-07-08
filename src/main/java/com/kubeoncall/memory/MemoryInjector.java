package com.kubeoncall.memory;

import java.util.Map;

public interface MemoryInjector {

    MemoryInjection inject(String query, Map<String, String> filters);

    static MemoryInjector noop() {
        return (query, filters) -> MemoryInjection.empty();
    }
}
