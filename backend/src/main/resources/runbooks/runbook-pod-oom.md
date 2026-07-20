---
runbookId: runbook-pod-oom
title: Kubernetes Pod OOMKilled 处置 SOP
category: k8s-pod
owner: app
version: v1
document_type: runbook
source_type: runbook
---
# Kubernetes Pod OOMKilled 处置 SOP

## 适用范围与目标

适用于容器退出原因为 OOMKilled、内存达到 limit 或节点发生全局 OOM。目标是区分配置不足、流量增长、内存泄漏和节点压力，保留分析证据并恢复容量。

## 只读取证

1. 查看容器 lastState、退出码、restartCount、requests/limits、QoS、节点 MemoryPressure 和相关 events。
2. 拉取 OOM 前后的 working set、RSS、请求量、队列长度、GC/heap 指标，并与健康实例和历史基线对比。
3. 获取 previous 日志及运行时/内核 OOM 记录，确认是 cgroup limit 还是节点 OOM。
4. 关联发布、配置、缓存策略和大请求；记录增长曲线而非只记录峰值。

## 判断与处置

- limit 与稳定工作集不匹配：确认节点容量后灰度提高资源，并同步 request；不得无限制放大 limit。
- 发布后持续增长：先回滚或摘流，保留 heap dump/profile 条件，由 owner 修复泄漏。
- 突发流量：扩容、限流或降级优先，观察单实例内存是否回落。
- 节点全局 OOM：转入主机内存 SOP，检查同节点其他工作负载。

## 恢复验证与升级

确认重启计数稳定、内存曲线有界、Ready 副本满足要求、错误率恢复且节点无 MemoryPressure。关键服务多副本同时 OOM、可能泄漏敏感数据、节点 OOM 或需要修改生产资源/回滚时立即升级。

## 安全边界

禁止关闭内存 limit、直接修改运行 pod、无容量评估地批量加内存或在未授权位置保存 heap dump。变更必须灰度、可回滚并记录容量依据。
