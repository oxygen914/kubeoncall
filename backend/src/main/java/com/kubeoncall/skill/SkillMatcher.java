package com.kubeoncall.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;

@Component
public class SkillMatcher {

    private final KubeOnCallProperties properties;

    public SkillMatcher(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public List<Skill> match(String request, Map<String, Object> context, List<Skill> skills) {
        return matchResult(request, context, skills).matches().stream()
                .map(SkillMatch::skill)
                .toList();
    }

    public MatchResult matchResult(String request, Map<String, Object> context, List<Skill> skills) {
        MatchContext matchContext = matchContext(request, context);
        int threshold = Math.max(1, properties.getSkill().getActivationThreshold());
        int limit = Math.max(1, properties.getSkill().getMaxActiveSkills());
        List<SkillMatch> candidates = safeSkills(skills).stream()
                .map(skill -> score(skill, matchContext))
                .flatMap(Optional::stream)
                .filter(scored -> scored.score() >= threshold)
                .sorted(Comparator.comparingInt(SkillMatch::score).reversed().thenComparing(scored -> scored.skill()
                        .id()))
                .toList();
        boolean hasExactMatch = candidates.stream().anyMatch(SkillMatch::exactIdentifier);
        List<SkillMatch> selected = candidates.stream()
                .filter(scored -> !hasExactMatch || scored.exactIdentifier())
                .limit(limit)
                .toList();
        return new MatchResult(selected, candidates.size(), candidates.size() > 1);
    }

    public boolean canActivateRequested(String request, Map<String, Object> context, Skill skill) {
        return requestedMatch(request, context, skill).isPresent();
    }

    public Optional<SkillMatch> requestedMatch(String request, Map<String, Object> context, Skill skill) {
        if (skill == null) {
            return Optional.empty();
        }
        return score(skill, matchContext(request, context))
                .map(match -> new SkillMatch(skill, match.score(), MatchSource.REQUESTED, match.exactIdentifier()));
    }

    private Optional<SkillMatch> score(Skill skill, MatchContext context) {
        if (context.taskType() != null
                && !skill.applicableTasks().isEmpty()
                && !skill.applicableTasks().contains(context.taskType())) {
            return Optional.empty();
        }
        if (context.resourceType() != null
                && !skill.resourceTypes().isEmpty()
                && !containsExact(skill.resourceTypes(), context.resourceType())) {
            return Optional.empty();
        }

        boolean alertNameMatch = containsExact(skill.alertNames(), context.alertName());
        boolean runbookMatch = containsExact(skill.runbookIds(), context.runbookId());
        boolean metricMatch = containsExact(skill.metricNames(), context.metricName());
        boolean exactIdentifier = alertNameMatch || runbookMatch || metricMatch;
        int score = 0;
        MatchSource source = MatchSource.NONE;
        if (alertNameMatch) {
            score += 24;
            source = MatchSource.ALERT_NAME;
        }
        if (runbookMatch) {
            score += 20;
            if (source == MatchSource.NONE) {
                source = MatchSource.RUNBOOK_ID;
            }
        }
        if (metricMatch) {
            score += 16;
            if (source == MatchSource.NONE) {
                source = MatchSource.METRIC_NAME;
            }
        }

        boolean triggerMatch = false;
        for (String trigger : skill.triggers()) {
            if (contains(context.haystack(), trigger)) {
                score += 4;
                triggerMatch = true;
            }
        }
        boolean serviceMatch = false;
        for (String service : skill.services()) {
            if (contains(context.haystack(), service)) {
                score += 3;
                serviceMatch = true;
            }
        }
        boolean nameMatch = contains(context.haystack(), skill.name());
        if (nameMatch) {
            score += 2;
        }
        if (!exactIdentifier && !triggerMatch && !serviceMatch && !nameMatch) {
            return Optional.empty();
        }
        if (source == MatchSource.NONE) {
            source = triggerMatch ? MatchSource.TRIGGER : serviceMatch ? MatchSource.SERVICE : MatchSource.NAME;
        }

        if (context.category() != null && containsExact(skill.categories(), context.category())) {
            score += 3;
        }
        if (context.resourceType() != null && containsExact(skill.resourceTypes(), context.resourceType())) {
            score += 2;
        }
        for (String tag : skill.tags()) {
            if (contains(context.haystack(), tag)) {
                score += 1;
            }
        }
        if (context.taskType() != null && skill.applicableTasks().contains(context.taskType())) {
            score += 3;
        }
        return Optional.of(new SkillMatch(skill, score, source, exactIdentifier));
    }

    private MatchContext matchContext(String request, Map<String, Object> context) {
        String haystack = haystack(request, context);
        return new MatchContext(
                haystack,
                taskType(haystack, context),
                contextValue(context, "resourceType", "resource_type"),
                contextValue(context, "alertName", "alertname", "alert_name"),
                contextValue(context, "metricName", "metric_name", "kubeoncall_metric"),
                contextValue(context, "runbookId", "runbook_id"),
                contextValue(context, "policyCategory", "alarmCategory", "category"));
    }

    private TaskType taskType(String haystack, Map<String, Object> context) {
        if (context != null) {
            for (String key : List.of("taskType", "task_type", "plannerTaskType")) {
                TaskType parsed = parseTaskType(context.get(key));
                if (parsed != null) {
                    return parsed;
                }
            }
            Object currentTask = context.get("currentTask");
            if (currentTask instanceof Task task) {
                return task.taskType();
            }
        }
        if (containsAny(
                haystack,
                "restart service",
                "restart the service",
                "rollout restart",
                "please restart",
                "帮我重启",
                "执行重启",
                "重启服务")) {
            return TaskType.RESTART_SERVICE;
        }
        if (containsAny(haystack, "scale workload", "scale up", "scale down", "帮我扩容", "执行扩容", "执行缩容")) {
            return TaskType.SCALE_WORKLOAD;
        }
        if (containsAny(haystack, "patch config", "配置修改", "改配置")) {
            return TaskType.PATCH_CONFIG;
        }
        if (containsAny(haystack, "clean data", "清理数据")) {
            return TaskType.CLEAN_DATA;
        }
        if (containsAny(haystack, "execute script", "执行脚本")) {
            return TaskType.EXECUTE_SCRIPT;
        }
        if (containsAny(haystack, "query logs", "logs", "log", "日志")) {
            return TaskType.QUERY_LOGS;
        }
        if (containsAny(haystack, "query metrics", "metrics", "cpu", "memory", "指标", "监控")) {
            return TaskType.QUERY_METRICS;
        }
        return null;
    }

    private TaskType parseTaskType(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return TaskType.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String contextValue(Map<String, Object> context, String... keys) {
        if (context == null) {
            return null;
        }
        for (String key : keys) {
            String normalized = normalize(context.get(key));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private boolean containsExact(List<String> values, String expected) {
        if (expected == null || values == null) {
            return false;
        }
        return values.stream().map(this::normalize).anyMatch(expected::equals);
    }

    private String normalize(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private boolean containsAny(String haystack, String... values) {
        for (String value : values) {
            if (haystack.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String haystack, String needle) {
        return needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT));
    }

    private String haystack(String request, Map<String, Object> context) {
        StringBuilder builder = new StringBuilder(request == null ? "" : request);
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null) {
                    builder.append(' ').append(key).append('=').append(value);
                }
            });
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private List<Skill> safeSkills(List<Skill> skills) {
        return skills == null ? List.of() : skills;
    }

    public enum MatchSource {
        ALERT_NAME,
        RUNBOOK_ID,
        METRIC_NAME,
        TRIGGER,
        SERVICE,
        NAME,
        REQUESTED,
        NONE
    }

    public record SkillMatch(Skill skill, int score, MatchSource source, boolean exactIdentifier) {}

    public record MatchResult(List<SkillMatch> matches, int candidateCount, boolean candidateConflict) {
        public MatchResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    private record MatchContext(
            String haystack,
            TaskType taskType,
            String resourceType,
            String alertName,
            String metricName,
            String runbookId,
            String category) {}
}
