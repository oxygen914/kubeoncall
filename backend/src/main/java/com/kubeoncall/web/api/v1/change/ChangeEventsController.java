package com.kubeoncall.web.api.v1.change;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * {@code /api/v1/change-events} — read-only change-event timeline (WBS-11 GAP-11-01). Requires
 * {@code change:read}. Serves whichever read source {@link ChangeCorrelationService} is configured
 * for (Redis, MySQL or SHADOW), so the same endpoint works before, during and after the cutover.
 * The windowed query is bounded; paging is applied in memory over the bounded result set.
 */
@RestController
@RequestMapping("/api/v1/change-events")
public class ChangeEventsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ChangeCorrelationService correlationService;
    private final V1Security security;

    public ChangeEventsController(ChangeCorrelationService correlationService, V1Security security) {
        this.correlationService = correlationService;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<ChangeEventView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String cluster,
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.CHANGE_READ);
        if (from != null && to != null && from.isAfter(to)) {
            throw V1ApiException.of(400, V1ApiErrorCode.INVALID_REQUEST, "from must not be after to");
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<ChangeEvent> events = correlationService.findBetween(from, to, cluster, namespace);
        long total = events.size();
        int offset = Math.max(0, (normalizedPage - 1) * normalizedSize);
        List<ChangeEventView> pageItems = events.stream()
                .skip(offset)
                .limit(normalizedSize)
                .map(ChangeEventsController::toView)
                .toList();
        return PageMeta.ListEnvelope.of(pageItems, normalizedPage, normalizedSize, total, currentRequestId());
    }

    private static String currentRequestId() {
        return RequestIdFilter.currentRequestId();
    }

    private static ChangeEventView toView(ChangeEvent event) {
        return new ChangeEventView(
                event.changeId(),
                event.changeType(),
                event.changedBy(),
                event.changedAt(),
                event.resourceType(),
                event.resourceName(),
                event.namespace(),
                event.cluster(),
                event.diff(),
                event.changeSource(),
                event.correlationId());
    }

    public record ChangeEventView(
            String changeId,
            String changeType,
            String changedBy,
            Instant changedAt,
            String resourceType,
            String resourceName,
            String namespace,
            String cluster,
            Map<String, Object> diff,
            String changeSource,
            String correlationId) {}
}
