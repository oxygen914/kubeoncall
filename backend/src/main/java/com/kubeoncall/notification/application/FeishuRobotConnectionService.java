package com.kubeoncall.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.provider.feishu.FeishuRobotRegistry;

/**
 * Upper-layer Feishu robot facade.
 *
 * <p>Callers select only a local {@code robotId}; provider credentials remain in server-side
 * configuration. Connecting a robot durably queues a standard verification notification and writes
 * an operation audit in the same transaction.
 */
public class FeishuRobotConnectionService {

    public static final String CONNECTION_EVENT_TYPE = "integration.feishu.robot.connection-test";
    private static final String ROUTING_KEY = "feishu-robot";

    private final FeishuRobotRegistry robotRegistry;
    private final NotificationSubmissionService submissionService;
    private final OperationAuditWriter auditWriter;
    private final Clock clock;

    public FeishuRobotConnectionService(
            FeishuRobotRegistry robotRegistry,
            NotificationSubmissionService submissionService,
            OperationAuditWriter auditWriter,
            Clock clock) {
        this.robotRegistry = Objects.requireNonNull(robotRegistry, "robotRegistry");
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ConnectionResult connect(ConnectionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String robotId;
        try {
            robotId = FeishuRobotRegistry.requireRobotId(command.robotId());
        } catch (IllegalArgumentException exception) {
            throw new ConnectionException(Code.INVALID_ROBOT_ID, exception.getMessage());
        }
        FeishuRobotRegistry.Robot robot = robotRegistry
                .find(robotId)
                .orElseThrow(
                        () -> new ConnectionException(Code.NOT_FOUND, "Feishu robot is not configured: " + robotId));
        String requestId = requireText(command.requestId(), "requestId");
        String idempotencySeed = idempotencySeed(command.idempotencyKey(), requestId);
        Instant now = clock.instant();
        NotificationMessage message = connectionMessage(robotId, idempotencySeed, now);
        NotificationPublishResult publishResult =
                submissionService.submitToDestination(message, robot.destination(), requestId);

        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action("integration.feishu.robot.connect")
                .resource("feishu_robot", resourceId(robotId))
                .result("SUCCESS")
                .reason("Feishu robot connection verification requested")
                .before(Map.of("configured", true))
                .after(Map.of(
                        "robotId",
                        robotId,
                        "status",
                        publishResult.status().name(),
                        "deliveryIds",
                        publishResult.deliveryIds()))
                .requestId(NotificationDeliveryIds.requestId(requestId))
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        return new ConnectionResult(
                robotId, publishResult.eventId(), publishResult.status(), publishResult.deliveryIds());
    }

    private static NotificationMessage connectionMessage(String robotId, String idempotencySeed, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("机器人 ID", robotId);
        facts.put("投递方式", "飞书自定义群机器人 Webhook");
        facts.put("验收边界", "平台受理后仍需人工确认测试群真实可见");
        return new NotificationMessage(
                "feishu-connect-" + digest(robotId + "\u0000" + idempotencySeed).substring(0, 32),
                CONNECTION_EVENT_TYPE,
                ROUTING_KEY,
                NotificationPriority.NORMAL,
                "KubeOnCall 飞书机器人接入验证",
                "KubeOnCall 已通过服务端托管配置发起机器人接入验证。",
                facts,
                List.of(),
                now);
    }

    private static String idempotencySeed(String idempotencyKey, String requestId) {
        if (idempotencyKey == null) {
            return requestId;
        }
        if (idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new ConnectionException(
                    Code.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must contain 16 to 128 characters");
        }
        return idempotencyKey;
    }

    private static String resourceId(String robotId) {
        return "fbot_" + digest(robotId).substring(0, 32);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record ConnectionCommand(
            String robotId,
            String idempotencyKey,
            Long actorId,
            String actorDisplayName,
            String requestId,
            String sourceIp,
            String userAgent) {}

    public record ConnectionResult(
            String robotId, String eventId, NotificationPublishResult.Status status, List<String> deliveryIds) {}

    public enum Code {
        INVALID_ROBOT_ID,
        INVALID_IDEMPOTENCY_KEY,
        NOT_FOUND
    }

    public static final class ConnectionException extends RuntimeException {

        private final Code code;

        ConnectionException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}
