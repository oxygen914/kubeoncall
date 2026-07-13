package com.kubeoncall.alarm.suppression;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

@Service
public class AlarmSuppressionService {

    private static final Logger log = LoggerFactory.getLogger(AlarmSuppressionService.class);
    private static final String KEY_PREFIX = "alarm-suppression:rule:";

    private final StringRedisTemplate redisTemplate;
    private final AlarmSuppressionRuleRepository repository;

    public AlarmSuppressionService(StringRedisTemplate redisTemplate, AlarmSuppressionRuleRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    public void recordSources(NormalizedAlarmEvent event) {
        if (event == null) {
            return;
        }
        for (AlarmSuppressionRule rule : repository.findAll()) {
            if (!matches(rule.source(), event)) {
                continue;
            }
            correlationKey(rule, event).ifPresent(key -> {
                try {
                    if (event.status() == AlarmStatus.RESOLVED) {
                        redisTemplate.delete(key);
                    } else {
                        String value = event.fingerprint() == null ? event.alertName() : event.fingerprint();
                        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(rule.ttlSeconds()));
                    }
                } catch (RuntimeException ex) {
                    log.warn(
                            "Failed to update suppression source: rule={}, errorType={}",
                            rule.id(),
                            ex.getClass().getSimpleName());
                }
            });
        }
    }

    public SuppressionDecision evaluate(NormalizedAlarmEvent event) {
        if (event == null || event.status() == AlarmStatus.RESOLVED) {
            return SuppressionDecision.none();
        }
        for (AlarmSuppressionRule rule : repository.findAll()) {
            if (!matches(rule.target(), event)) {
                continue;
            }
            Optional<String> key = correlationKey(rule, event);
            if (key.isEmpty()) {
                continue;
            }
            try {
                String sourceFingerprint = redisTemplate.opsForValue().get(key.get());
                if (sourceFingerprint != null && !sourceFingerprint.isBlank()) {
                    String reason = rule.reason() == null || rule.reason().isBlank()
                            ? "Suppressed by active root-cause alarm rule " + rule.id()
                            : rule.reason();
                    return new SuppressionDecision(
                            true, rule.id(), repository.activeVersion(), key.get(), sourceFingerprint, reason);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Suppression lookup failed; alarm processing continues: rule={}, errorType={}",
                        rule.id(),
                        ex.getClass().getSimpleName());
            }
        }
        return SuppressionDecision.none();
    }

    boolean matches(AlarmSuppressionRule.Match match, NormalizedAlarmEvent event) {
        if (match == null) {
            return false;
        }
        boolean resourceMatches =
                match.resourceTypes().isEmpty() || match.resourceTypes().contains(event.resourceType());
        boolean alertMatches = match.alertNamePatterns().isEmpty()
                || match.alertNamePatterns().stream().anyMatch(pattern -> wildcardMatches(event.alertName(), pattern));
        return resourceMatches && alertMatches;
    }

    Optional<String> correlationKey(AlarmSuppressionRule rule, NormalizedAlarmEvent event) {
        List<String> values = rule.correlateBy().stream()
                .map(field -> correlationValue(field, event))
                .toList();
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            return Optional.empty();
        }
        String encoded = values.stream()
                .map(value -> URLEncoder.encode(value.trim().toLowerCase(Locale.ROOT), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + ":" + right)
                .orElse("");
        return Optional.of(KEY_PREFIX + rule.id() + ":" + encoded);
    }

    private String correlationValue(String field, NormalizedAlarmEvent event) {
        if (field == null) {
            return null;
        }
        return switch (field.toLowerCase(Locale.ROOT)) {
            case "cluster" -> event.cluster();
            case "namespace" -> event.namespace();
            case "service" -> event.service();
            case "resource" -> event.resourceName();
            case "node" ->
                firstNonBlank(
                        event.labels().get("node"),
                        event.labels().get("nodeName"),
                        event.labels().get("kubernetes.io/hostname"),
                        event.annotations().get("node"),
                        event.resourceType() == AlarmResourceType.NODE ? event.resourceName() : null);
            default ->
                field.regionMatches(true, 0, "label.", 0, "label.".length())
                        ? event.labels().get(field.substring("label.".length()))
                        : null;
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean wildcardMatches(String actual, String expectedPattern) {
        if (actual == null || expectedPattern == null) {
            return false;
        }
        String regex = Pattern.quote(expectedPattern).replace("*", "\\E.*\\Q");
        return actual.matches("(?i)^" + regex + "$");
    }

    public record SuppressionDecision(
            boolean suppressed,
            String ruleId,
            String ruleVersion,
            String suppressionKey,
            String sourceFingerprint,
            String reason) {
        static SuppressionDecision none() {
            return new SuppressionDecision(false, null, null, null, null, "");
        }
    }
}
