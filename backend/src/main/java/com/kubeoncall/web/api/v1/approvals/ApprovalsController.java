package com.kubeoncall.web.api.v1.approvals;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;

/** Read-only approval request projection for the operations console. */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalsController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<MySqlApprovalRepository> repositoryProvider;
    private final V1Security security;

    public ApprovalsController(ObjectProvider<MySqlApprovalRepository> repositoryProvider, V1Security security) {
        this.repositoryProvider = repositoryProvider;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<ApprovalListItem> list(
            @RequestParam(name = "page", defaultValue = "1") int requestedPage,
            @RequestParam(name = "size", defaultValue = "20") int requestedSize,
            @RequestParam(name = "status", required = false) String statuses,
            @RequestParam(name = "risk", required = false) String riskLevels,
            @RequestParam(name = "executionId", required = false) String executionId) {
        security.requirePermission(PermissionCode.APPROVAL_READ);
        MySqlApprovalRepository repository = repository();
        int page = normalizedPage(requestedPage);
        int size = normalizedSize(requestedSize);
        MySqlApprovalRepository.ApprovalPage result = repository.list(new MySqlApprovalRepository.ApprovalQuery(
                page, size, commaSeparated(statuses), commaSeparated(riskLevels), executionId));
        List<ApprovalListItem> items =
                result.rows().stream().map(ApprovalListItem::from).toList();
        return PageMeta.ListEnvelope.of(items, page, size, result.total(), RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{approvalId}")
    public ApiResponse<ApprovalDetail> detail(@PathVariable String approvalId) {
        security.requirePermission(PermissionCode.APPROVAL_READ);
        ApprovalRequestRecord record = repository().findByPublicId(approvalId).orElseThrow(() -> notFound(approvalId));
        return ApiResponse.ok(ApprovalDetail.from(record), RequestIdFilter.currentRequestId());
    }

    private MySqlApprovalRepository repository() {
        MySqlApprovalRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Approval read model is not available");
        }
        return repository;
    }

    private static V1ApiException notFound(String approvalId) {
        return V1ApiException.notFound("Approval request not found: " + approvalId);
    }

    private static int normalizedPage(int page) {
        return Math.max(1, page);
    }

    private static int normalizedSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private static String approvalSummary(ApprovalRequestRecord record) {
        Object value = record.context().get("summary");
        return value instanceof String text && !text.isBlank() ? text : record.actionType();
    }

    private static Map<String, Object> decisionView(ApprovalRequestRecord record) {
        if (record.decision() == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("value", record.decision());
        value.put("decidedBy", record.decidedBy());
        value.put("decidedAt", record.decidedAt());
        value.put("comment", record.comment());
        return value;
    }

    public record ApprovalListItem(
            String id,
            String executionId,
            String status,
            String riskLevel,
            String summary,
            Instant createdAt,
            Instant expiresAt,
            long version) {

        private static ApprovalListItem from(ApprovalRequestRecord record) {
            return new ApprovalListItem(
                    record.publicId(),
                    record.executionPublicId(),
                    record.status(),
                    record.riskLevel(),
                    approvalSummary(record),
                    record.createdAt(),
                    record.expiresAt(),
                    record.version());
        }
    }

    public record ApprovalDetail(
            String id,
            String executionId,
            String status,
            String riskLevel,
            String summary,
            Instant createdAt,
            Instant expiresAt,
            long version,
            String action,
            Map<String, Object> context,
            Map<String, Object> decision) {

        private static ApprovalDetail from(ApprovalRequestRecord record) {
            return new ApprovalDetail(
                    record.publicId(),
                    record.executionPublicId(),
                    record.status(),
                    record.riskLevel(),
                    approvalSummary(record),
                    record.createdAt(),
                    record.expiresAt(),
                    record.version(),
                    record.actionType(),
                    record.context(),
                    decisionView(record));
        }
    }
}
