package com.kubeoncall.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class NotificationDomainTest {

    @Test
    void messageDefensivelyCopiesProviderNeutralContent() {
        Map<String, String> facts = new HashMap<>();
        facts.put("resource", "node-a");
        List<NotificationAction> actions = new ArrayList<>();
        actions.add(new NotificationAction("open", "Open console", URI.create("https://koc.example/alarms/1"), true));

        NotificationMessage message = new NotificationMessage(
                "evt-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.CRITICAL,
                "NodeDown",
                "Node exporter is unavailable",
                facts,
                actions,
                Instant.parse("2026-07-30T01:00:00Z"));

        facts.put("secret", "must-not-appear");
        actions.clear();

        assertEquals(Map.of("resource", "node-a"), message.facts());
        assertEquals(1, message.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> message.facts().put("cluster", "prod"));
        assertThrows(
                UnsupportedOperationException.class, () -> message.actions().clear());
    }

    @Test
    void destinationSnapshotsCapabilitiesAndAttributes() {
        Set<NotificationCapability> capabilities = new HashSet<>();
        capabilities.add(NotificationCapability.GROUP_WEBHOOK);
        Map<String, String> attributes = new HashMap<>();
        attributes.put("audience", "infra");

        NotificationDestination destination =
                new NotificationDestination("feishu-primary", "FEISHU", "infra-primary", capabilities, attributes);

        capabilities.clear();
        attributes.clear();

        assertEquals("feishu", destination.providerKey());
        assertEquals(Set.of(NotificationCapability.GROUP_WEBHOOK), destination.requiredCapabilities());
        assertEquals(Map.of("audience", "infra"), destination.attributes());
    }

    @Test
    void routeRejectsDuplicateDestinationIds() {
        NotificationDestination first = destination("primary", "feishu");
        NotificationDestination duplicate = destination("primary", "dingtalk");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> new NotificationRoute("oncall", List.of(first, duplicate)));

        assertEquals("Duplicate destination id in route oncall: primary", exception.getMessage());
    }

    @Test
    void actionRejectsNonHttpLinks() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationAction("open", "Open", URI.create("javascript:alert(1)"), true));

        assertEquals("action url must be an absolute HTTP(S) URL", exception.getMessage());
    }

    @Test
    void destinationRejectsWebhookUrlAsTargetAlias() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationDestination(
                        "feishu-primary",
                        "feishu",
                        "https://open.feishu.cn/open-apis/bot/v2/hook/secret",
                        Set.of(),
                        Map.of()));

        assertEquals("target must be a configuration alias, not a URL", exception.getMessage());
    }

    private static NotificationDestination destination(String id, String provider) {
        return new NotificationDestination(
                id, provider, provider + "-target", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
    }
}
