package com.kubeoncall.alarm.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Boundary-neutral representation of an inbound alarm before normalization.
 *
 * <p>Transport DTOs implement this contract so alarm governance never depends on the web layer.
 */
public interface AlarmIngress {

    String alarmId();

    String dedupKey();

    String source();

    String severity();

    String nodeName();

    String summary();

    Instant occurredAt();

    Map<String, Object> metadata();

    String fingerprint();

    String alertName();

    String resourceType();

    String resourceName();

    String cluster();

    String namespace();

    String service();

    String metricName();

    Double currentValue();

    Double threshold();

    String unit();

    String duration();

    Map<String, String> labels();

    Map<String, String> annotations();

    String runbookId();

    String status();
}
