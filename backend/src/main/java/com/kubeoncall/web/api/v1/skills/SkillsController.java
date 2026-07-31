package com.kubeoncall.web.api.v1.skills;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.skill.SkillGovernanceException;
import com.kubeoncall.skill.SkillGovernanceService;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/** Versioned Skill governance API backed by the durable MySQL state projection. */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillsController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<SkillGovernanceService> serviceProvider;
    private final V1Security security;

    public SkillsController(ObjectProvider<SkillGovernanceService> serviceProvider, V1Security security) {
        this.serviceProvider = serviceProvider;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<SkillView> list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String loadStatus,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        security.requirePermission(PermissionCode.SKILL_READ);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        SkillStateRepository.SkillStatePage result = run(() -> requiredService()
                .list(new SkillStateRepository.SkillStateQuery(
                        enabled,
                        firstNonBlank(loadStatus, status),
                        firstNonBlank(query, q),
                        normalizedPage,
                        normalizedSize)));
        return PageMeta.ListEnvelope.of(
                result.items().stream().map(SkillView::from).toList(),
                normalizedPage,
                normalizedSize,
                result.total(),
                RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{skillId}")
    public ResponseEntity<ApiResponse<SkillView>> detail(@PathVariable String skillId) {
        security.requirePermission(PermissionCode.SKILL_READ);
        SkillView view = SkillView.from(run(() -> requiredService().find(skillId)));
        return withVersion(view.version(), ApiResponse.ok(view, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/{skillId}/enabled")
    public ResponseEntity<ApiResponse<SkillView>> setEnabled(
            @PathVariable String skillId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) EnabledRequest body,
            HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.SKILL_MANAGE);
        if (body == null) {
            throw invalid("Request body is required");
        }
        SkillGovernanceService.ChangeEnabledCommand command = new SkillGovernanceService.ChangeEnabledCommand(
                skillId,
                parseIfMatch(ifMatch),
                body.enabled(),
                principal.user().id(),
                principal.user().displayName(),
                RequestIdFilter.currentRequestId(),
                clientIp(request),
                request.getHeader(HttpHeaders.USER_AGENT));
        SkillView view = SkillView.from(run(() -> requiredService().setEnabled(command)));
        return withVersion(view.version(), ApiResponse.ok(view, RequestIdFilter.currentRequestId()));
    }

    @PostMapping("/reload")
    public ResponseEntity<ApiResponse<ReloadAccepted>> reload(HttpServletRequest request) {
        V1Principal principal = requireUser(PermissionCode.SKILL_MANAGE);
        String requestId = RequestIdFilter.currentRequestId();
        AsyncTaskRecord task = run(() -> requiredService()
                .requestReload(new SkillGovernanceService.ReloadCommand(
                        principal.user().id(),
                        principal.user().displayName(),
                        Instant.now(),
                        requestId,
                        request.getHeader("X-Trace-Id"),
                        clientIp(request),
                        request.getHeader(HttpHeaders.USER_AGENT))));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.created(new ReloadAccepted(task.publicId(), task.status()), requestId));
    }

    private V1Principal requireUser(String permission) {
        V1Principal principal = security.requirePermission(permission);
        if (principal.user() == null || principal.user().id() <= 0) {
            throw V1ApiException.forbidden("A user-backed session is required for Skill commands");
        }
        return principal;
    }

    private SkillGovernanceService requiredService() {
        SkillGovernanceService service = serviceProvider.getIfAvailable();
        if (service == null || !service.isAvailable()) {
            throw unavailable("Skill governance service is not available");
        }
        return service;
    }

    private static <T> T run(ServiceCall<T> call) {
        try {
            return call.run();
        } catch (SkillGovernanceException ex) {
            throw switch (ex.code()) {
                case INVALID -> invalid(ex.getMessage());
                case NOT_FOUND -> V1ApiException.notFound(ex.getMessage());
                case CONFLICT -> V1ApiException.conflict(V1ApiErrorCode.CONFLICT, ex.getMessage());
                case VERSION_CONFLICT ->
                    V1ApiException.conflict(V1ApiErrorCode.RESOURCE_VERSION_CONFLICT, ex.getMessage());
                case UNAVAILABLE -> unavailable(ex.getMessage());
            };
        }
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw invalid("If-Match version header is required");
        }
        try {
            long version = Long.parseLong(ifMatch.replace("\"", "").trim());
            if (version <= 0) {
                throw invalid("If-Match must be a positive numeric version");
            }
            return version;
        } catch (NumberFormatException ex) {
            throw invalid("If-Match must be a positive numeric version");
        }
    }

    private static <T> ResponseEntity<T> withVersion(long version, T body) {
        return ResponseEntity.ok().eTag("\"" + version + "\"").body(body);
    }

    private static V1ApiException invalid(String message) {
        return new V1ApiException(HttpStatus.BAD_REQUEST.value(), V1ApiErrorCode.INVALID_REQUEST, message);
    }

    private static V1ApiException unavailable(String message) {
        return new V1ApiException(HttpStatus.SERVICE_UNAVAILABLE.value(), V1ApiErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @FunctionalInterface
    private interface ServiceCall<T> {

        T run();
    }

    public record EnabledRequest(@NotNull Boolean enabled) {}

    public record ReloadAccepted(String taskId, String status) {}

    public record SkillView(
            String id,
            String name,
            String description,
            String skillVersion,
            String checksum,
            String sourceLocation,
            boolean enabled,
            String loadStatus,
            String errorSummary,
            java.util.List<String> tags,
            java.util.List<String> applicableTasks,
            String maxRisk,
            java.util.List<String> triggers,
            java.util.List<String> services,
            java.util.List<String> allowedTools,
            Instant lastLoadedAt,
            Map<String, Object> metadata,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        private static SkillView from(SkillStateRecord record) {
            Map<String, Object> metadata = record.metadata() == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(record.metadata()));
            return new SkillView(
                    record.skillId(),
                    text(metadata, "name"),
                    text(metadata, "description"),
                    record.skillVersion(),
                    record.checksum(),
                    record.sourceLocation(),
                    record.enabled(),
                    record.loadStatus(),
                    record.errorSummary(),
                    stringList(metadata, "tags"),
                    stringList(metadata, "applicableTasks"),
                    text(metadata, "maxRisk"),
                    stringList(metadata, "triggers"),
                    stringList(metadata, "services"),
                    stringList(metadata, "toolWhitelist"),
                    record.lastLoadedAt(),
                    metadata,
                    record.version(),
                    record.createdAt(),
                    record.updatedAt());
        }

        private static String text(Map<String, Object> values, String key) {
            Object value = values.get(key);
            return value == null ? null : String.valueOf(value);
        }

        private static java.util.List<String> stringList(Map<String, Object> values, String key) {
            Object value = values.get(key);
            if (!(value instanceof Iterable<?> iterable)) {
                return java.util.List.of();
            }
            java.util.List<String> result = new java.util.ArrayList<>();
            for (Object item : iterable) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return java.util.List.copyOf(result);
        }
    }
}
