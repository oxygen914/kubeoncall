package com.kubeoncall.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.agent.planner.PlannerLlmDecision;
import com.kubeoncall.agent.planner.PlannerLlmResult;
import com.kubeoncall.agent.planner.PlannerLlmService;
import com.kubeoncall.alarm.correlation.ChangeCorrelation;
import com.kubeoncall.alarm.correlation.ChangeCorrelationService;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.readmodel.AlarmQueryService;
import com.kubeoncall.alarm.readmodel.AlarmQueryService.AlarmListItem;
import com.kubeoncall.monitoring.MonitoringViews.AdviceFeed;
import com.kubeoncall.monitoring.MonitoringViews.AdviceItem;
import com.kubeoncall.monitoring.MonitoringViews.AlarmChangeCorrelation;
import com.kubeoncall.monitoring.MonitoringViews.CorrelationFeed;
import com.kubeoncall.monitoring.MonitoringViews.RelatedChange;
import com.kubeoncall.monitoring.MonitoringViews.Scope;
import com.kubeoncall.monitoring.MonitoringViews.Summary;

/**
 * Cross-domain operations read model for the monitoring console.
 *
 * <p>It keeps alarm/change correlation and AI advice server-side so the browser never needs raw
 * PromQL, model credentials, or direct access to internal read stores. Advice is read-only: model
 * output is treated as a hypothesis and cannot create or execute a remediation.
 */
@Service
public class MonitoringOperationsService {

    private final MonitoringQueryService monitoring;
    private final AlarmQueryService alarms;
    private final ChangeCorrelationService changes;
    private final PlannerLlmService plannerLlm;

    public MonitoringOperationsService(
            MonitoringQueryService monitoring,
            AlarmQueryService alarms,
            ChangeCorrelationService changes,
            PlannerLlmService plannerLlm) {
        this.monitoring = monitoring;
        this.alarms = alarms;
        this.changes = changes;
        this.plannerLlm = plannerLlm;
    }

    public CorrelationFeed correlations(Scope scope, Duration window, int limit) {
        if (!alarms.isAvailable()) {
            return new CorrelationFeed(scope, false, false, List.of(), Instant.now());
        }
        AlarmQueryService.AlarmListResult alarmPage = alarms.list(new AlarmQueryService.AlarmListRequest(
                1,
                Math.max(1, Math.min(20, limit)),
                "severity,asc",
                "FIRING",
                null,
                scope.cluster(),
                scope.namespace(),
                null,
                null));
        List<AlarmChangeCorrelation> result = new ArrayList<>();
        boolean changeDataAvailable = true;
        for (AlarmListItem alarm : alarmPage.items()) {
            try {
                List<RelatedChange> related = changes.findRelatedChanges(normalized(alarm)).stream()
                        .filter(correlation -> withinWindow(correlation, window))
                        .map(MonitoringOperationsService::relatedChange)
                        .toList();
                if (!related.isEmpty()) {
                    result.add(new AlarmChangeCorrelation(
                            alarm.id(),
                            alarm.alertName(),
                            alarm.severity(),
                            alarm.status(),
                            alarm.resource().name(),
                            alarm.firstSeen(),
                            related));
                }
            } catch (RuntimeException ex) {
                changeDataAvailable = false;
            }
        }
        return new CorrelationFeed(scope, true, changeDataAvailable, List.copyOf(result), Instant.now());
    }

    public AdviceFeed advice(Scope scope, Duration correlationWindow, boolean includeCorrelations) {
        Summary summary = monitoring.summary(scope.cluster(), scope.environment(), scope.namespace());
        CorrelationFeed correlationFeed = includeCorrelations
                ? correlations(scope, correlationWindow, 5)
                : new CorrelationFeed(scope, false, false, List.of(), Instant.now());
        Map<String, Object> evidence = evidence(summary, correlationFeed);
        PlannerLlmResult plannerResult = plannerLlm.planWithStatus(
                "Generate a read-only Kubernetes operations recommendation from the supplied monitoring evidence. "
                        + "Do not execute, mutate, or bypass approval.",
                evidence);
        PlannerLlmDecision decision = plannerResult.decision();

        List<AdviceItem> items = new ArrayList<>();
        if (decision != null) {
            items.add(modelAdvice(decision, summary));
        }
        addRuleAdvice(items, summary, correlationFeed);
        if (items.isEmpty()) {
            items.add(new AdviceItem(
                    "scope-stable",
                    "当前范围未发现明显异常",
                    "INFO",
                    "节点 Ready、异常 Pod 与 CPU 快照未触发运营风险规则。",
                    summary.totalNodes() + " 个节点，异常 Pod 0，平均 CPU " + displayPercent(summary.averageCpuUsagePercent()),
                    "保持当前刷新频率，并在变更窗口后复核健康趋势。",
                    "RULE_ENGINE_FALLBACK",
                    "/monitoring"));
        }
        return new AdviceFeed(
                scope,
                plannerResult.mode().name(),
                decision != null,
                "READ_ONLY",
                items.stream().limit(3).toList(),
                Instant.now());
    }

    private static Map<String, Object> evidence(Summary summary, CorrelationFeed correlations) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("cluster", summary.cluster());
        evidence.put("totalNodes", summary.totalNodes());
        evidence.put("readyNodes", summary.readyNodes());
        evidence.put("notReadyNodes", summary.notReadyNodes());
        evidence.put("unknownNodes", summary.unknownNodes());
        evidence.put("podPhaseCounts", summary.podPhaseCounts());
        evidence.put("averageCpuUsagePercent", summary.averageCpuUsagePercent());
        evidence.put("dataSources", summary.dataSources());
        evidence.put("alarmChangeCorrelations", correlations.correlations());
        return Map.copyOf(evidence);
    }

    private static AdviceItem modelAdvice(PlannerLlmDecision decision, Summary summary) {
        String recommendation = hasText(decision.summary()) ? decision.summary() : "继续执行只读诊断，确认信号完整后再进入需要审批的处置流程。";
        String risk = decision.riskLevel() == null
                ? "INFO"
                : switch (decision.riskLevel()) {
                    case CRITICAL, HIGH -> "P1";
                    case MEDIUM -> "P2";
                    case LOW -> "P3";
                };
        return new AdviceItem(
                "model-overview",
                "AI 综合研判",
                risk,
                "已基于当前监控快照生成只读运营建议。",
                summary.readyNodes() + "/" + summary.totalNodes() + " Ready，平均 CPU "
                        + displayPercent(summary.averageCpuUsagePercent()),
                recommendation,
                "MODEL",
                "/ask");
    }

    private static void addRuleAdvice(List<AdviceItem> items, Summary summary, CorrelationFeed correlationFeed) {
        if (summary.notReadyNodes() > 0 || summary.unknownNodes() > 0) {
            items.add(new AdviceItem(
                    "node-health",
                    "优先核查节点健康",
                    summary.notReadyNodes() > 0 ? "P1" : "P2",
                    "当前范围存在 NotReady 或状态未知节点。",
                    summary.notReadyNodes() + " NotReady，" + summary.unknownNodes() + " Unknown",
                    "先查看节点事件、kubelet 与网络状态，再评估是否需要隔离或迁移工作负载。",
                    "RULE_ENGINE_FALLBACK",
                    "/monitoring"));
        }
        long abnormalPods = summary.podPhaseCounts().entrySet().stream()
                .filter(entry -> List.of("Pending", "Failed", "Unknown").contains(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        if (abnormalPods > 0) {
            items.add(new AdviceItem(
                    "abnormal-pods",
                    "处理异常工作负载",
                    "P2",
                    "当前范围存在非健康阶段 Pod。",
                    abnormalPods + " 个 Pending / Failed / Unknown Pod",
                    "按异常优先列表检查调度约束、镜像拉取、探针和最近重启原因。",
                    "RULE_ENGINE_FALLBACK",
                    "/monitoring"));
        }
        if (summary.averageCpuUsagePercent() != null && summary.averageCpuUsagePercent() >= 80) {
            items.add(new AdviceItem(
                    "high-cpu",
                    "确认 CPU 压力来源",
                    summary.averageCpuUsagePercent() >= 90 ? "P1" : "P2",
                    "节点平均 CPU 已进入高负载区间。",
                    "平均 CPU " + displayPercent(summary.averageCpuUsagePercent()),
                    "对比上一周期趋势并定位热点节点，优先执行只读进程与工作负载分析。",
                    "RULE_ENGINE_FALLBACK",
                    "/monitoring"));
        }
        if (!correlationFeed.correlations().isEmpty()) {
            AlarmChangeCorrelation correlation = correlationFeed.correlations().get(0);
            RelatedChange change = correlation.changes().get(0);
            items.add(new AdviceItem(
                    "alarm-change",
                    "复核告警前变更",
                    "P2",
                    "活跃告警与近期变更存在可解释关联。",
                    correlation.alertName() + " ↔ " + change.changeType() + "，评分 " + Math.round(change.score() * 100)
                            + "%",
                    change.suggestions().isEmpty()
                            ? "对比变更前后配置与指标，再决定是否进入审批回滚流程。"
                            : change.suggestions().get(0),
                    "RULE_ENGINE_FALLBACK",
                    "/changes"));
        }
    }

    private static NormalizedAlarmEvent normalized(AlarmListItem alarm) {
        AlarmResourceType resourceType =
                AlarmResourceType.fromRaw(alarm.resource().type());
        return new NormalizedAlarmEvent(
                alarm.id(),
                alarm.fingerprint(),
                alarm.alertName(),
                "monitoring-read-model",
                alarm.severity(),
                AlarmSeverity.fromRaw(alarm.severity()),
                resourceType,
                alarm.resource().name(),
                alarm.resource().cluster(),
                alarm.resource().namespace(),
                alarm.resource().service(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                alarm.firstSeen(),
                alarm.alertName(),
                Map.of());
    }

    private static boolean withinWindow(ChangeCorrelation correlation, Duration window) {
        if (window == null || correlation.changeEvent() == null) {
            return true;
        }
        return correlation.changeEvent().changedAt().isAfter(Instant.now().minus(window));
    }

    private static RelatedChange relatedChange(ChangeCorrelation correlation) {
        var change = correlation.changeEvent();
        return new RelatedChange(
                change.changeId(),
                change.changeType(),
                change.resourceName(),
                change.namespace(),
                change.changedAt(),
                Math.round(correlation.correlationScore() * 100.0) / 100.0,
                correlation.correlationReason(),
                correlation.suggestions());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String displayPercent(Double value) {
        return value == null ? "不可用" : String.format(java.util.Locale.ROOT, "%.2f%%", value);
    }
}
