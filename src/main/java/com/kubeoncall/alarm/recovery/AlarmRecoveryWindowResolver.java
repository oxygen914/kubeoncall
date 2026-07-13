package com.kubeoncall.alarm.recovery;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class AlarmRecoveryWindowResolver {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)\\bfor\\s+(\\d+)\\s*([smhd])\\b");

    private final KubeOnCallProperties properties;

    public AlarmRecoveryWindowResolver(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public Duration recoveryWindow(String expression, AlarmSeverity severity) {
        if (expression != null) {
            Matcher matcher = DURATION_PATTERN.matcher(expression);
            if (matcher.find()) {
                long amount = Long.parseLong(matcher.group(1));
                return switch (matcher.group(2).toLowerCase()) {
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    case "d" -> Duration.ofDays(amount);
                    default -> fallbackWindow(severity);
                };
            }
        }
        return fallbackWindow(severity);
    }

    public Duration stateTtl(Duration stableWindow) {
        long seconds = Math.max(properties.getAlarm().getRecoveryStateTtlSeconds(), stableWindow.toSeconds() + 3600);
        return Duration.ofSeconds(seconds);
    }

    public Duration finalRetention() {
        return Duration.ofSeconds(Math.max(3600, properties.getAlarm().getResolvedRetentionSeconds()));
    }

    private Duration fallbackWindow(AlarmSeverity severity) {
        long seconds =
                switch (severity == null ? AlarmSeverity.P3 : severity) {
                    case P0 -> properties.getAlarm().getP0RecoveryWindowSeconds();
                    case P1 -> properties.getAlarm().getP1RecoveryWindowSeconds();
                    case P2 -> properties.getAlarm().getP2RecoveryWindowSeconds();
                    case P3 -> properties.getAlarm().getP3RecoveryWindowSeconds();
                    case INFO -> properties.getAlarm().getInfoRecoveryWindowSeconds();
                };
        return Duration.ofSeconds(Math.max(0, seconds));
    }
}
