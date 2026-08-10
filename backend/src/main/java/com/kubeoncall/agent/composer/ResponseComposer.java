package com.kubeoncall.agent.composer;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.PlannerSummary;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConfidenceAssessment;

@Component
public class ResponseComposer {

    private static final Pattern READY_SNAPSHOT =
            Pattern.compile("(\\d+\\s*/\\s*\\d+\\s*Ready)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CPU_SNAPSHOT =
            Pattern.compile("(?:平均\\s*)?CPU(?:\\s*使用率)?\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?%)", Pattern.CASE_INSENSITIVE);

    public String compose(GraphState state) {
        StringBuilder builder = new StringBuilder();
        builder.append(opening(state));

        String snapshot = snapshot(state.getUserRequest());
        if (!snapshot.isBlank()) {
            builder.append("\n\n当前快照：").append(snapshot).append("。");
        }

        builder.append("\n\n结论：").append(conclusion(state, snapshot));
        builder.append("\n\n证据可信度：").append(confidence(state));

        String evidenceGap = evidenceGap(state);
        if (!evidenceGap.isBlank()) {
            builder.append("；当前仍缺少").append(evidenceGap).append("，结论需结合后续指标复核");
        }
        builder.append("。");

        builder.append("\n\n建议：").append(recommendation(state));
        if (isReadOnly(state.getCurrentTask())) {
            builder.append("\n\n本次仅执行只读检查，未对集群进行任何变更。");
        }
        return builder.toString();
    }

    private String opening(GraphState state) {
        return switch (state.getStatus()) {
            case SUCCESS -> "研判已完成。";
            case PAUSED -> "研判已完成，建议动作正在等待人工审批。";
            case FAILED, REJECTED -> "本次研判未能完整完成。";
            case REPLAN_REQUIRED -> "当前证据不足，正在等待补充信息后重新研判。";
            case RUNNING -> "正在结合当前监控范围进行研判。";
        };
    }

    private String snapshot(String request) {
        String ready = match(READY_SNAPSHOT, request);
        String cpu = match(CPU_SNAPSHOT, request);
        if (!ready.isBlank() && !cpu.isBlank()) {
            return normalizeReady(ready) + "，平均 CPU " + cpu;
        }
        if (!ready.isBlank()) {
            return normalizeReady(ready);
        }
        return cpu.isBlank() ? "" : "平均 CPU " + cpu;
    }

    private String conclusion(GraphState state, String snapshot) {
        AiConclusion conclusion = firstConclusion(state);
        if (conclusion != null && conversational(conclusion.claim())) {
            return sentence(conclusion.claim());
        }
        String request = defaultString(state.getUserRequest(), "").toLowerCase();
        boolean cpuQuestion = request.contains("cpu") || request.contains("处理器");
        if (state.getStatus() == com.kubeoncall.domain.graph.GraphStatus.FAILED
                || state.getStatus() == com.kubeoncall.domain.graph.GraphStatus.REJECTED) {
            return "执行链路存在失败节点，当前不能给出完整的运行状态判断。";
        }
        if (cpuQuestion && snapshot.contains("Ready") && snapshot.contains("CPU")) {
            return "节点均处于 Ready 状态，当前平均 CPU 使用率较低，暂未发现需要立即处置的 CPU 压力。";
        }
        if (cpuQuestion && snapshot.contains("CPU")) {
            return "当前平均 CPU 使用率较低，暂未发现需要立即处置的 CPU 压力。";
        }
        if (executorSucceeded(state)) {
            return "只读检查已成功完成，当前证据未显示需要立即处置的异常。";
        }
        return "已形成初步判断，但仍需补充运行指标后再确认具体原因。";
    }

    private String confidence(GraphState state) {
        AiConclusion conclusion = firstConclusion(state);
        if (conclusion != null && conclusion.confidence() != null) {
            ConfidenceAssessment value = conclusion.confidence();
            return confidenceLabel(value.label()) + "（" + Math.round(value.score() * 100) + "%）";
        }
        PlannerSummary planner = getPlannerSummary(state);
        return confidenceLabel(planner == null ? null : planner.confidence());
    }

    private String evidenceGap(GraphState state) {
        PlannerSummary planner = getPlannerSummary(state);
        if (planner == null) {
            return "";
        }
        List<String> missing = planner.missingSignals() == null ? List.of() : planner.missingSignals();
        List<String> translated =
                missing.stream().map(this::translateSignal).distinct().limit(4).toList();
        String summary = defaultString(planner.summary(), "");
        if (summary.contains("NAMESPACE_NOT_ALLOWED") && !translated.contains("跨 Namespace 指标")) {
            translated = new java.util.ArrayList<>(translated);
            translated.add("跨 Namespace 指标");
        }
        return String.join("、", translated);
    }

    private String recommendation(GraphState state) {
        Task task = state.getCurrentTask();
        if (state.getStatus() == com.kubeoncall.domain.graph.GraphStatus.PAUSED) {
            return "请先核对影响范围和回滚方案，再由具备权限的值班人员审批。";
        }
        if (state.getStatus() == com.kubeoncall.domain.graph.GraphStatus.FAILED
                || state.getStatus() == com.kubeoncall.domain.graph.GraphStatus.REJECTED) {
            return "请在执行详情中查看失败节点，修复对应证据源或工具连接后重新发起研判。";
        }
        if (task != null && task.taskType() == TaskType.QUERY_METRICS) {
            String request = defaultString(state.getUserRequest(), "").toLowerCase();
            if (request.contains("cpu") || request.contains("处理器")) {
                return "继续观察 CPU 趋势；若持续超过告警阈值，再检查 user、system、iowait、steal 分项和高 CPU 进程。";
            }
            return "继续观察相关指标趋势；若持续超过告警阈值，再结合基线、时间窗口和资源明细定位原因。";
        }
        if (task != null && task.taskType() == TaskType.QUERY_LOGS) {
            return "结合事件时间线和上下游日志继续确认根因，证据不足时不要直接执行重启或配置变更。";
        }
        return "持续观察当前范围；如指标恶化或出现新告警，再结合实时证据升级处置。";
    }

    private AiConclusion firstConclusion(GraphState state) {
        Object value = state.getContext().get("conclusions");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(AiConclusion.class::isInstance)
                    .map(AiConclusion.class::cast)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private boolean executorSucceeded(GraphState state) {
        return "success".equalsIgnoreCase(readExecutorResultStatus(state));
    }

    private boolean isReadOnly(Task task) {
        return task != null && (task.taskType() == TaskType.QUERY_METRICS || task.taskType() == TaskType.QUERY_LOGS);
    }

    private boolean conversational(String claim) {
        if (claim == null || claim.isBlank() || claim.length() > 400) {
            return false;
        }
        return !claim.contains("Collected evidence:") && !claim.contains("This conclusion does not authorize");
    }

    private String sentence(String value) {
        String normalized = value.trim();
        return normalized.matches(".*[。！？.!?]$") ? normalized : normalized + "。";
    }

    private String confidenceLabel(String value) {
        if (value == null) {
            return "待确认";
        }
        return switch (value.trim().toUpperCase()) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> "待确认";
        };
    }

    private String translateSignal(String value) {
        if (value == null || value.isBlank()) {
            return "补充证据";
        }
        return switch (value.trim().toLowerCase()) {
            case "service_metadata_unavailable" -> "服务元数据";
            case "node_cpu_seconds_total" -> "CPU 分项时序";
            case "uptime" -> "节点运行时长";
            case "top_output" -> "主机进程快照";
            case "process_cpu_breakdown" -> "进程 CPU 明细";
            default -> value.replace('_', ' ');
        };
    }

    private String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(defaultString(value, ""));
        return matcher.find() ? matcher.group(1) : "";
    }

    private String normalizeReady(String value) {
        return value.trim().replaceAll("\\s*/\\s*", "/").replaceAll("(?i)\\s*Ready$", " Ready");
    }

    private PlannerSummary getPlannerSummary(GraphState state) {
        Object value = state.getContext().get("plannerSummary");
        return value instanceof PlannerSummary summary ? summary : null;
    }

    private String readExecutorResultStatus(GraphState state) {
        Object value = state.getContext().get("executorResult");
        if (value instanceof Map<?, ?> map) {
            Object status = map.get("status");
            return status == null ? "not_executed" : String.valueOf(status);
        }
        return "not_executed";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
