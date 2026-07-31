---
runbookId: runbook-host-memory-pressure
title: 主机内存压力与 OOM 处置 SOP
category: host
owner: infra
version: v1
document_type: runbook
source_type: runbook
---
# 主机内存压力与 OOM 处置 SOP

## 适用范围与目标

适用于可用内存持续下降、swap 抖动、内核 OOM 或节点 MemoryPressure。目标是识别缓存增长、进程泄漏、容器超限和节点容量不足，保护关键业务并保留 OOM 证据。

## 只读取证

1. 查看 `free -m`、`vmstat 1 5`、`ps -eo pid,comm,rss,vsz,%mem --sort=-rss`，区分 available、page cache、swap 与匿名内存。
2. 查看 `dmesg -T` 或系统日志中的 OOM killer 记录，记录被杀进程、cgroup、时间和调用栈。
3. 对容器节点检查 pod requests/limits、working set、重启次数和节点驱逐事件；关联最近发布及流量变化。
4. 比较同服务其他实例，判断单实例泄漏还是整体容量问题。

## 判断与处置

- 单实例持续增长：先扩容或摘流保护服务，保留 heap/profile 证据后由 owner 决定重启。不得在取证前批量重启。
- limits 设置过低：评估节点余量后提交资源配置变更，灰度一个实例并观察；禁止临时取消 limits。
- 节点整体压力：优先新增容量或受控迁移低风险工作负载。驱逐和 cordon/drain 必须审批。
- page cache 较高但 available 正常：不执行 drop_caches，结合回收速率与业务指标判断是否真实压力。

## 恢复验证与升级

连续两个窗口确认 available 稳定、swap/OOM 不再增长、节点 MemoryPressure 解除、服务错误率恢复。发生连续 OOM、关键服务被杀、节点无法调度或需要 drain/reboot 时立即升级。

## 安全边界

禁止未审批执行 drop_caches、swapoff、重启节点、批量驱逐或修改内核参数。所有缓解动作必须具备容量校验、回滚步骤和 owner 确认。
