package com.kubeoncall.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        String haystack = haystack(request, context);
        TaskType taskType = taskType(haystack, context);
        int threshold = Math.max(1, properties.getSkill().getActivationThreshold());
        int limit = Math.max(1, properties.getSkill().getMaxActiveSkills());
        return skills.stream()
                .map(skill -> new ScoredSkill(skill, score(skill, haystack, taskType)))
                .filter(scored -> scored.score() >= threshold)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed().thenComparing(scored -> scored.skill()
                        .id()))
                .limit(limit)
                .map(ScoredSkill::skill)
                .toList();
    }

    public boolean canActivateRequested(String request, Map<String, Object> context, Skill skill) {
        if (skill == null) {
            return false;
        }
        String haystack = haystack(request, context);
        TaskType taskType = taskType(haystack, context);
        if (taskType != null
                && !skill.applicableTasks().isEmpty()
                && !skill.applicableTasks().contains(taskType)) {
            return false;
        }
        return contains(haystack, skill.name())
                || skill.triggers().stream().anyMatch(value -> contains(haystack, value))
                || skill.services().stream().anyMatch(value -> contains(haystack, value))
                || skill.resourceTypes().stream().anyMatch(value -> contains(haystack, value))
                || skill.tags().stream().anyMatch(value -> contains(haystack, value));
    }

    private int score(Skill skill, String haystack, TaskType taskType) {
        if (taskType != null
                && !skill.applicableTasks().isEmpty()
                && !skill.applicableTasks().contains(taskType)) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        for (String trigger : skill.triggers()) {
            if (contains(haystack, trigger)) {
                score += 4;
            }
        }
        for (String service : skill.services()) {
            if (contains(haystack, service)) {
                score += 3;
            }
        }
        if (contains(haystack, skill.name())) {
            score += 2;
        }
        // applicableTasks, tags and generic resource types constrain/rank an already relevant
        // skill. They must not activate an unrelated domain skill by themselves.
        if (score == 0) {
            return 0;
        }
        for (String resourceType : skill.resourceTypes()) {
            if (contains(haystack, resourceType)) {
                score += 2;
            }
        }
        for (String tag : skill.tags()) {
            if (contains(haystack, tag)) {
                score += 2;
            }
        }
        if (taskType != null && skill.applicableTasks().contains(taskType)) {
            score += 3;
        }
        return score;
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
        if (containsAny(haystack, "query logs", "logs", "日志")) {
            return TaskType.QUERY_LOGS;
        }
        if (containsAny(haystack, "query metrics", "metrics", "cpu", "memory", "指标", "监控")) {
            return TaskType.QUERY_METRICS;
        }
        if (containsAny(haystack, "restart", "重启")) {
            return TaskType.RESTART_SERVICE;
        }
        if (containsAny(haystack, "scale", "扩容", "缩容")) {
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

    private record ScoredSkill(Skill skill, int score) {}
}
