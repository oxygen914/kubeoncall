package com.kubeoncall.approval.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;

/** Safe approval notification projection that intentionally excludes raw execution context. */
public final class ApprovalNotificationMessageFactory {

    private ApprovalNotificationMessageFactory() {}

    public static NotificationMessage requested(ApprovalRequestRecord approval) {
        return message(
                approval,
                "approval.requested",
                "等待人工审批",
                "工作流已暂停，等待有权限的人员在 KubeOnCall 中审批",
                approval.requestedAt(),
                approval.status());
    }

    public static NotificationMessage decided(ApprovalRequestRecord approval, String decision, Instant decidedAt) {
        return message(
                approval, "approval.decided", "审批结果：" + decision, "审批状态已更新，最终事实以 KubeOnCall 记录为准", decidedAt, decision);
    }

    private static NotificationMessage message(
            ApprovalRequestRecord approval,
            String eventType,
            String title,
            String summary,
            Instant occurredAt,
            String status) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "审批 ID", approval.publicId());
        put(facts, "执行 ID", approval.executionPublicId());
        put(facts, "动作类型", approval.actionType());
        put(facts, "风险级别", approval.riskLevel());
        put(facts, "状态", status);
        Instant eventTime = occurredAt == null ? Instant.now() : occurredAt;
        return new NotificationMessage(
                eventId(eventType, approval.publicId(), eventTime),
                eventType,
                "approval",
                priority(approval.riskLevel()),
                title,
                summary,
                facts,
                List.of(),
                eventTime);
    }

    private static NotificationPriority priority(String riskLevel) {
        if (riskLevel == null) {
            return NotificationPriority.NORMAL;
        }
        return switch (riskLevel.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> NotificationPriority.CRITICAL;
            case "HIGH" -> NotificationPriority.HIGH;
            case "LOW" -> NotificationPriority.LOW;
            default -> NotificationPriority.NORMAL;
        };
    }

    private static String eventId(String eventType, String approvalId, Instant occurredAt) {
        String canonical = eventType + "\u0000" + approvalId + "\u0000" + occurredAt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "nevt_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            String normalized = value.trim();
            facts.put(key, normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000));
        }
    }
}
