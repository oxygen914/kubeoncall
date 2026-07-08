package com.kubeoncall.alarm.policy;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Matches a {@link NormalizedAlarmEvent} against the configured policies and produces an
 * {@link AlarmEvaluationResult}.
 *
 * <p>Matching is by {@code alertName} first (exact), then by {@code metricName/resourceType/labels}.
 * A policy's numeric trigger ({@code currentValue <operator> threshold}) must be satisfied for the
 * policy to fire. When multiple policies match, the most severe wins — this is what makes
 * {@code CPU 85.1} resolve to {@code HostHighCpuUsageP0} rather than the P1 sibling.
 *
 * <p>Impact-factor adjustments (env/tier/scope) are applied to the matched policy's base severity.
 * The final severity is the max of the adjusted base and the explicit upstream severity, so an
 * upstream {@code critical} can never be silently downgraded.
 */
@Service
public class AlarmPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmPolicyEngine.class);

    private final AlarmPolicyRepository policyRepository;
    private final AlarmSeverity defaultSeverity;

    public AlarmPolicyEngine(AlarmPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
        this.defaultSeverity = (policyRepository instanceof YamlAlarmPolicyRepository yaml)
                ? yaml.defaultSeverity()
                : AlarmSeverity.P3;
    }

    public AlarmEvaluationResult evaluate(NormalizedAlarmEvent event) {
        List<AlarmPolicy> candidates = findCandidates(event);
        List<AlarmPolicy> fired = new ArrayList<>();
        for (AlarmPolicy policy : candidates) {
            if (matches(policy, event) && triggerSatisfied(policy, event)) {
                fired.add(policy);
            }
        }
        if (fired.isEmpty()) {
            return AlarmEvaluationResult.unmatched(defaultSeverity, "No policy matched the alarm event");
        }
        // Most severe fired policy wins; ties broken by declaration order (stable).
        AlarmPolicy winner = fired.stream()
                .min((a, b) -> Integer.compare(a.severity().rank(), b.severity().rank()))
                .orElse(fired.get(0));

        AlarmSeverity adjusted = applyImpactFactors(winner.severity(), event);
        AlarmSeverity finalSeverity = maxSeverity(adjusted, event.severity());

        String workflowTemplate = winner.actions() != null ? winner.actions().workflowTemplate() : null;
        String reason = String.format("Matched policy %s (%s); final severity %s",
                winner.id(), winner.name(), finalSeverity);
        return new AlarmEvaluationResult(
                true,
                winner,
                winner.id(),
                finalSeverity,
                winner.condition().threshold(),
                winner.runbookId(),
                winner.promql(),
                winner.window(),
                workflowTemplate,
                reason,
                List.of());
    }

    private List<AlarmPolicy> findCandidates(NormalizedAlarmEvent event) {
        List<AlarmPolicy> all = policyRepository.findAll();
        if (event.alertName() != null && !event.alertName().isBlank()) {
            // Include exact alertName matches, but also include sibling policies for the same
            // metric/resource so a P1 upstream alert can still escalate to the P0 policy when the
            // current value crosses the higher threshold.
            List<AlarmPolicy> byNameOrMetric = new ArrayList<>();
            for (AlarmPolicy p : all) {
                if (event.alertName().equalsIgnoreCase(p.name())) {
                    byNameOrMetric.add(p);
                    continue;
                }
                if (event.metricName() != null
                        && p.metricName() != null
                        && event.metricName().equalsIgnoreCase(p.metricName())
                        && (event.resourceType() == null || p.resourceType() == null || event.resourceType() == p.resourceType())) {
                    byNameOrMetric.add(p);
                }
            }
            if (!byNameOrMetric.isEmpty()) {
                return byNameOrMetric;
            }
        }
        return all;
    }

    private boolean matches(AlarmPolicy policy, NormalizedAlarmEvent event) {
        AlarmCondition cond = policy.condition();
        if (cond.metricName() != null && !cond.metricName().isBlank()
                && event.metricName() != null
                && !cond.metricName().equalsIgnoreCase(event.metricName())) {
            return false;
        }
        if (cond.resourceType() != null && event.resourceType() != null
                && cond.resourceType() != event.resourceType()) {
            return false;
        }
        if (cond.labels() != null && !cond.labels().isEmpty()) {
            for (Map.Entry<String, String> required : cond.labels().entrySet()) {
                String actual = event.labels().get(required.getKey());
                if (actual == null || !actual.equalsIgnoreCase(required.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean triggerSatisfied(AlarmPolicy policy, NormalizedAlarmEvent event) {
        AlarmCondition cond = policy.condition();
        if (cond.threshold() == null) {
            // State/root-cause policy (e.g. KubeNodeNotReady): fires on match alone.
            return true;
        }
        return cond.thresholdSatisfied(event.currentValue());
    }

    private AlarmSeverity applyImpactFactors(AlarmSeverity base, NormalizedAlarmEvent event) {
        int delta = 0;
        String env = labelOrMetadata(event, "env");
        String tier = labelOrMetadata(event, "tier");
        if ("dev".equalsIgnoreCase(env) || "lab".equalsIgnoreCase(env)) {
            // Non-prod downgrades one level, but P0 root-cause stays P0.
            delta += 1;
        }
        if ("tier0".equalsIgnoreCase(tier)) {
            delta -= 1;
        }
        if (delta == 0) {
            return base;
        }
        AlarmSeverity adjusted = base.adjust(delta);
        // Never downgrade a P0 root-cause alarm in non-prod.
        if (base == AlarmSeverity.P0 && adjusted.rank() > AlarmSeverity.P0.rank()) {
            return AlarmSeverity.P0;
        }
        return adjusted;
    }

    private static String labelOrMetadata(NormalizedAlarmEvent event, String key) {
        String v = event.labels().get(key);
        if (v != null) {
            return v;
        }
        Object meta = event.metadata().get(key);
        return meta == null ? null : String.valueOf(meta);
    }

    private static AlarmSeverity maxSeverity(AlarmSeverity a, AlarmSeverity b) {
        if (b == null) {
            return a;
        }
        return a.rank() <= b.rank() ? a : b;
    }

    AlarmSeverity defaultSeverity() {
        return defaultSeverity;
    }

    static AlarmResourceType resourceTypeOf(AlarmPolicy policy) {
        return policy.resourceType();
    }
}
