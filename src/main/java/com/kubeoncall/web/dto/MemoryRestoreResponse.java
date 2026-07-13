package com.kubeoncall.web.dto;

import java.time.Instant;

public record MemoryRestoreResponse(String memoryId, String status, Instant restoredAt, String previousDeleteReason) {}
