package com.kubeoncall.web.api.v1.executions;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.PageMeta;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

/**
 * Read-only workflow execution projection for the operations console.
 *
 * <p>The controller deliberately exposes stable public identifiers and summaries rather than
 * database identifiers, deduplication keys or Redis graph-state keys.
 */
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionsController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ObjectProvider<WorkflowExecutionRepository> repositoryProvider;
    private final V1Security security;

    public ExecutionsController(ObjectProvider<WorkflowExecutionRepository> repositoryProvider, V1Security security) {
        this.repositoryProvider = repositoryProvider;
        this.security = security;
    }

    @GetMapping
    public PageMeta.ListEnvelope<ExecutionListItem> list(
            @RequestParam(name = "page", defaultValue = "1") int requestedPage,
            @RequestParam(name = "size", defaultValue = "20") int requestedSize,
            @RequestParam(name = "status", required = false) String statuses,
            @RequestParam(name = "type", required = false) String types,
            @RequestParam(name = "triggerType", required = false) String triggerType,
            @RequestParam(name = "triggerId", required = false) String triggerId,
            @RequestParam(name = "alarmId", required = false) String alarmId) {
        security.requirePermission(PermissionCode.EXECUTION_READ);
        WorkflowExecutionRepository repository = repository();
        int page = normalizedPage(requestedPage);
        int size = normalizedSize(requestedSize);
        String triggerPublicId = firstNonBlank(triggerId, alarmId);
        WorkflowExecutionRepository.ExecutionPage result =
                repository.list(new WorkflowExecutionRepository.ExecutionQuery(
                        page, size, commaSeparated(statuses), commaSeparated(types), triggerType, triggerPublicId));
        List<ExecutionListItem> items =
                result.rows().stream().map(ExecutionListItem::from).toList();
        return PageMeta.ListEnvelope.of(items, page, size, result.total(), RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{executionId}")
    public ApiResponse<ExecutionDetail> detail(@PathVariable String executionId) {
        security.requirePermission(PermissionCode.EXECUTION_READ);
        WorkflowExecutionRepository repository = repository();
        WorkflowExecutionRecord execution =
                repository.findByPublicId(executionId).orElseThrow(() -> notFound(executionId));
        List<ExecutionNodeView> nodes = repository.listNodes(executionId).stream()
                .map(ExecutionNodeView::from)
                .toList();
        String currentNode =
                nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).nodeName();
        return ApiResponse.ok(ExecutionDetail.from(execution, currentNode, nodes), RequestIdFilter.currentRequestId());
    }

    /**
     * Compatibility endpoint used by the timeline panel. The execution detail already embeds the
     * same node list, so clients may avoid this extra request.
     */
    @GetMapping("/{executionId}/nodes")
    public ApiResponse<List<ExecutionNodeView>> nodes(@PathVariable String executionId) {
        security.requirePermission(PermissionCode.EXECUTION_READ);
        WorkflowExecutionRepository repository = repository();
        if (repository.findByPublicId(executionId).isEmpty()) {
            throw notFound(executionId);
        }
        List<ExecutionNodeView> nodes = repository.listNodes(executionId).stream()
                .map(ExecutionNodeView::from)
                .toList();
        return ApiResponse.ok(nodes, RequestIdFilter.currentRequestId());
    }

    private WorkflowExecutionRepository repository() {
        WorkflowExecutionRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new V1ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    V1ApiErrorCode.SERVICE_UNAVAILABLE,
                    "Workflow execution read model is not available");
        }
        return repository;
    }

    private static V1ApiException notFound(String executionId) {
        return V1ApiException.notFound("Workflow execution not found: " + executionId);
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

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private static Long calculateDurationMs(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    public record ExecutionListItem(
            String id,
            String type,
            String status,
            String summary,
            String triggerId,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            long version) {

        private static ExecutionListItem from(WorkflowExecutionRecord record) {
            return new ExecutionListItem(
                    record.publicId(),
                    record.type(),
                    record.status(),
                    record.summary(),
                    record.triggerPublicId(),
                    record.startedAt(),
                    record.finishedAt(),
                    calculateDurationMs(record.startedAt(), record.finishedAt()),
                    record.version());
        }
    }

    public record ExecutionDetail(
            String id,
            String type,
            String status,
            String summary,
            String triggerId,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            long version,
            String riskLevel,
            String currentNode,
            String resultSummary,
            String errorCode,
            String errorSummary,
            String requestId,
            String traceId,
            List<ExecutionNodeView> nodes) {

        private static ExecutionDetail from(
                WorkflowExecutionRecord record, String currentNode, List<ExecutionNodeView> nodes) {
            return new ExecutionDetail(
                    record.publicId(),
                    record.type(),
                    record.status(),
                    record.summary(),
                    record.triggerPublicId(),
                    record.startedAt(),
                    record.finishedAt(),
                    calculateDurationMs(record.startedAt(), record.finishedAt()),
                    record.version(),
                    record.riskLevel(),
                    currentNode,
                    record.resultSummary(),
                    record.errorCode(),
                    record.errorSummary(),
                    record.requestId(),
                    record.traceId(),
                    nodes);
        }
    }

    public record ExecutionNodeView(
            String id,
            String nodeName,
            String nodeType,
            String status,
            int attempt,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String outputSummary,
            String errorCode,
            String errorSummary,
            long version) {

        private static ExecutionNodeView from(WorkflowNodeExecutionRecord record) {
            return new ExecutionNodeView(
                    record.publicId(),
                    record.nodeName(),
                    record.nodeType(),
                    record.status(),
                    record.attempt(),
                    record.startedAt(),
                    record.finishedAt(),
                    record.durationMs(),
                    record.outputSummary(),
                    record.errorCode(),
                    record.errorSummary(),
                    record.version());
        }
    }
}
