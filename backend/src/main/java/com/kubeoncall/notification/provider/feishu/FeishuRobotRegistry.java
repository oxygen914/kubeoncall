package com.kubeoncall.notification.provider.feishu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;

/**
 * Resolves a caller-facing robot ID to a server-managed Feishu target.
 *
 * <p>The ID is a local account alias, equivalent to an OpenClaw channel account ID. It is not a
 * webhook token, App ID or other provider credential.
 */
public final class FeishuRobotRegistry {

    private static final Pattern ROBOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final String DESTINATION_PREFIX = "feishu-robot-";

    private final Map<String, Robot> robots;

    public FeishuRobotRegistry(Set<String> configuredTargetAliases) {
        Map<String, Robot> registered = new LinkedHashMap<>();
        if (configuredTargetAliases != null) {
            for (String configuredTargetAlias : configuredTargetAliases) {
                String robotId = requireRobotId(configuredTargetAlias);
                registered.put(
                        robotId,
                        new Robot(
                                robotId,
                                new NotificationDestination(
                                        DESTINATION_PREFIX + robotId,
                                        FeishuWebhookNotificationProvider.PROVIDER_KEY,
                                        robotId,
                                        Set.of(NotificationCapability.GROUP_WEBHOOK),
                                        Map.of("robotId", robotId))));
            }
        }
        this.robots = Map.copyOf(registered);
    }

    public Optional<Robot> find(String robotId) {
        return Optional.ofNullable(robots.get(requireRobotId(robotId)));
    }

    public int size() {
        return robots.size();
    }

    public static String requireRobotId(String robotId) {
        String normalized = robotId == null ? "" : robotId;
        if (!ROBOT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "robotId must contain 1 to 64 letters, digits, dots, underscores or hyphens");
        }
        return normalized;
    }

    public record Robot(String robotId, NotificationDestination destination) {}
}
