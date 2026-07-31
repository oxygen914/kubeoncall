package com.kubeoncall.notification.provider.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.notification.domain.NotificationCapability;

class FeishuRobotRegistryTest {

    @Test
    void mapsRobotIdToCredentialFreeDestination() {
        FeishuRobotRegistry registry = new FeishuRobotRegistry(Set.of("infra-primary"));

        FeishuRobotRegistry.Robot robot = registry.find("infra-primary").orElseThrow();

        assertThat(robot.robotId()).isEqualTo("infra-primary");
        assertThat(robot.destination().providerKey()).isEqualTo("feishu");
        assertThat(robot.destination().target()).isEqualTo("infra-primary");
        assertThat(robot.destination().requiredCapabilities()).containsExactly(NotificationCapability.GROUP_WEBHOOK);
        assertThat(robot.destination().target()).doesNotContain("://");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void rejectsUnsafeConfiguredRobotIdAtStartup() {
        assertThatThrownBy(() -> new FeishuRobotRegistry(Set.of("https://secret.example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("robotId");
        assertThatThrownBy(() -> new FeishuRobotRegistry(Set.of(" infra-primary ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("robotId");
    }
}
