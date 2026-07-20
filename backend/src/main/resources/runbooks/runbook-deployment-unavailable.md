---
runbookId: runbook-deployment-unavailable
title: Kubernetes Deployment 副本不可用处置 SOP
category: workload
owner: app
version: v1
document_type: runbook
source_type: runbook
---
# Kubernetes Deployment 副本不可用处置 SOP

## 适用范围与目标

适用于 availableReplicas 低于期望、ProgressDeadlineExceeded 或滚动发布卡住。目标是沿 Deployment、ReplicaSet、Pod、调度与依赖链定位原因，恢复足够健康副本。

## 只读取证

1. 查看 Deployment conditions、desired/updated/available replicas、strategy、revision 和 events。
2. 跟踪当前与上一 ReplicaSet，检查 pending、crash、not ready pod 的 events、日志、探针和调度失败原因。
3. 核对 PDB、配额、节点容量、镜像拉取、Secret/ConfigMap、服务依赖以及最近发布记录。
4. 确认业务入口实际健康实例数、错误率、延迟和可用区分布。

## 判断与处置

- 新版本不可用：满足回滚条件时通过发布系统回滚到健康 revision，保留失败 pod 证据。
- 调度不足：明确是配额、requests、亲和性、污点还是集群容量，优先补容量，不临时删除安全约束。
- 探针或依赖失败：处理根因后灰度验证，避免只扩大 progress deadline。
- 发布策略导致容量缺口：评估 maxUnavailable/maxSurge 与集群余量后提交配置变更。

## 恢复验证与升级

确认 availableReplicas 达标、发布完成、所有可用区有健康实例、错误率和延迟恢复，旧 ReplicaSet 缩容符合策略。关键服务容量为零、回滚也失败、集群无调度容量或需绕过 PDB/配额时立即升级。

## 安全边界

禁止直接编辑 ReplicaSet/Pod、盲目 scale 到零、绕过发布审计、删除 PDB 或批量强制删除 pod。生产变更必须由声明式配置完成并具备回滚 revision。
