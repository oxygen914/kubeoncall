package com.kubeoncall.web.api.v1.audit;

import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.audit.read.AuditDiff;
import com.kubeoncall.audit.read.AuditEventRecord;
import com.kubeoncall.audit.read.AuditEventRepository;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/** Read-only operation-audit API. No database internal ids are serialized. */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ObjectProvider<AuditEventRepository> repositoryProvider;
    private final SensitiveDataRedactor redactor;
    private final V1Security security;

    public AuditEventsController(
            ObjectProvider<AuditEventRepository> repositoryProvider,
            ObjectProvider<SensitiveDataRedactor> redactorProvider,
            V1Security security) {
        this.repositoryProvider = repositoryProvider;
        SensitiveDataRedactor configuredRedactor = redactorProvider.getIfAvailable();
        this.redactor = configuredRedactor == null ? SensitiveDataRedactor.STANDARD : configuredRedactor;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<AuditEventView> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.AUDIT_READ);
        validateTimeRange(from, to);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        AuditEventRepository.AuditPage events = repository()
                .list(new AuditEventRepository.AuditQuery(
                        actor,
                        action,
                        resourceType,
                        resourceId,
                        result,
                        requestId,
                        from,
                        to,
                        normalizedPage,
                        normalizedSize));
        return PageMeta.ListEnvelope.of(
                events.items().stream().map(this::toView).toList(),
                normalizedPage,
                normalizedSize,
                events.total(),
                currentRequestId());
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditEventView> detail(@PathVariable String auditId) {
        security.requirePermission(PermissionCode.AUDIT_READ);
        AuditEventRecord event = repository()
                .find(auditId)
                .orElseThrow(() -> V1ApiException.notFound("Audit event not found: " + auditId));
        return ApiResponse.ok(toView(event), currentRequestId());
    }

    private AuditEventRepository repository() {
        AuditEventRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Audit read model is not available");
        }
        return repository;
    }

    private AuditEventView toView(AuditEventRecord event) {
        Map<String, Object> before = redactor.redactMap(event.beforeState());
        Map<String, Object> after = redactor.redactMap(event.afterState());
        return new AuditEventView(
                event.publicId(),
                new ActorView(event.actorType(), event.actorPublicId(), redactor.redactText(event.actorDisplayName())),
                event.action(),
                new ResourceView(event.resourceType(), event.resourcePublicId()),
                event.result(),
                redactor.redactText(event.reason()),
                event.requestId(),
                event.traceId(),
                event.sourceIp(),
                redactor.redactText(event.browser()),
                event.occurredAt(),
                before,
                after,
                AuditDiff.between(before, after));
    }

    private static void validateTimeRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }

    private static String currentRequestId() {
        return RequestIdFilter.currentRequestId();
    }

    public record AuditEventView(
            String id,
            ActorView actor,
            String action,
            ResourceView resource,
            String result,
            String reason,
            String requestId,
            String traceId,
            String sourceIp,
            String browser,
            Instant occurredAt,
            Map<String, Object> before,
            Map<String, Object> after,
            AuditDiff diff) {}

    public record ActorView(String type, String id, String displayName) {}

    public record ResourceView(String type, String id) {}
}
