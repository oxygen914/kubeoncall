package com.kubeoncall.web.api.v1.dictionaries;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kubeoncall.web.api.v1.ApiResponse;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;

/**
 * {@code /api/v1/dictionaries} — enumerations the frontend must not hardcode. Each dictionary returns
 * stable {@code value}s (matching backend enums), localized labels, a rank for ordering and a
 * deprecated flag so retired values still render for historical data. Unknown values render as the
 * raw string in the UI rather than crashing.
 */
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionariesController {

    private static final Map<String, List<DictionaryItem>> DICTIONARIES = Map.of(
            "alarm-severities",
                    List.of(
                            new DictionaryItem("P0", "P0 紧急", "需要立即处理", 0, false),
                            new DictionaryItem("P1", "P1 高", "需快速响应", 1, false),
                            new DictionaryItem("P2", "P2 中", "工作时间内处理", 2, false),
                            new DictionaryItem("P3", "P3 低", "择机处理", 3, false),
                            new DictionaryItem("INFO", "信息", "仅记录", 4, false)),
            "alarm-statuses",
                    List.of(
                            new DictionaryItem("FIRING", "触发中", null, 0, false),
                            new DictionaryItem("ACKNOWLEDGED", "已确认", null, 1, false),
                            new DictionaryItem("RECOVERY_PENDING", "恢复待确认", null, 2, false),
                            new DictionaryItem("RESOLVED", "已恢复", null, 3, false)),
            "execution-statuses",
                    List.of(
                            new DictionaryItem("PENDING", "待执行", null, 0, false),
                            new DictionaryItem("RUNNING", "执行中", null, 1, false),
                            new DictionaryItem("WAITING_APPROVAL", "等待审批", null, 2, false),
                            new DictionaryItem("SUCCEEDED", "成功", null, 3, false),
                            new DictionaryItem("FAILED", "失败", null, 4, false),
                            new DictionaryItem("CANCELLED", "已取消", null, 5, false)),
            "approval-decisions",
                    List.of(
                            new DictionaryItem("APPROVED", "批准", null, 0, false),
                            new DictionaryItem("REJECTED", "拒绝", null, 1, false)),
            "resource-types",
                    List.of(
                            new DictionaryItem("NODE", "节点", null, 0, false),
                            new DictionaryItem("POD", "Pod", null, 1, false),
                            new DictionaryItem("DEPLOYMENT", "Deployment", null, 2, false),
                            new DictionaryItem("SERVICE", "Service", null, 3, false),
                            new DictionaryItem("CLUSTER", "集群", null, 4, false)));

    @GetMapping
    public ApiResponse<Map<String, List<DictionaryItem>>> all() {
        return ApiResponse.ok(DICTIONARIES, RequestIdFilter.currentRequestId());
    }

    @GetMapping("/{name}")
    public ApiResponse<List<DictionaryItem>> byName(@PathVariable String name) {
        List<DictionaryItem> items = DICTIONARIES.get(name);
        if (items == null) {
            throw new V1ApiException(
                    HttpStatus.NOT_FOUND.value(), V1ApiErrorCode.NOT_FOUND, "Unknown dictionary: " + name);
        }
        return ApiResponse.ok(items, RequestIdFilter.currentRequestId());
    }

    public record DictionaryItem(String value, String label, String description, int rank, boolean deprecated) {}
}
