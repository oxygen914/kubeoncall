---
id: pod-crashloop-triage
name: Pod CrashLoopBackOff Triage
version: v1
description: Diagnose repeated container exits and CrashLoopBackOff from last state, previous logs, events, and revision evidence.
triggers: [crashloopbackoff, pod crash loop, repeated container exits, container restart loop, previous container logs]
resourceTypes: [pod]
alertNames: [PodCrashLoopBackOffP1]
metricNames: [kube.pod.waiting_reason]
runbookIds: [runbook-pod-crashloop]
categories: [k8s-pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, pod, container, crashloop, restart]
maxRisk: LOW
toolWhitelist:
  - knowledge.searchSop
  - kubernetes.describeResource
  - kubernetes.getPods
  - kubernetes.describeWorkload
  - kubernetes.queryLogs
  - kubernetes.queryEvents
  - kubernetes.queryPodLogs
  - kubernetes.queryMetricsContext
  - prometheus.queryRange
  - prometheus.instantQuery
  - prometheus.rangeQuery
  - alerts.getActiveAlerts
  - alertmanager.listAlerts
---
# Pod CrashLoopBackOff Triage

## Diagnostic objective

Explain repeated container exits by separating application, configuration, dependency, probe, image, and resource evidence.

## Required live evidence

Collect last state, reason, exit code, restart count, events, revision, current and previous logs, probes, image digest, requests/limits, node, and release timing. Compare a healthy replica.

## Decision branches

- Exit code or stack trace is deterministic: correlate it with the affected revision and configuration source.
- Startup or liveness probe fails: verify real startup behavior and dependency reachability before blaming probe settings.
- OOMKilled is present: hand off to `pod-oom-triage`.
- Scheduling or node evidence leads the failure: hand off to the cluster-capacity or node Skill.

## Safe next step

Preserve previous-log and revision evidence; propose any change as a separate governed task.

## Prohibited actions

Do not loop-delete Pods, restart the workload, disable probes, expose Secret values, or patch live Pods from this Skill.

## Recovery and escalation

Require stable restart count, Ready replicas, clean error trends, and healthy dependencies across two windows. Escalate when no healthy replica exists, previous logs are unavailable, or a critical dependency is overloaded.
