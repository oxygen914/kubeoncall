---
runbookId: runbook-node-notready
title: Kubernetes Node NotReady 处置 SOP
category: k8s-node
owner: infra
version: v1
document_type: runbook
source_type: runbook
---
# Kubernetes Node NotReady 处置 SOP

## 适用范围与目标

适用于节点 Ready=False/Unknown、心跳中断或大批 pod 被驱逐。目标是判断控制面、网络、kubelet、运行时或节点资源故障，先保护业务副本，再恢复节点。

## 只读取证

1. 查看节点 conditions、taints、capacity、最近 events 和租约更新时间，确认是单节点还是集群面故障。
2. 检查受影响 pod、PDB、副本分布和关键服务剩余可用实例；先评估业务冗余。
3. 在节点可达时检查 kubelet、容器运行时、磁盘/内存压力、系统时间、证书和网络连通日志。
4. 对比同可用区节点，关联云平台事件、网络变更、升级和自动伸缩活动。

## 判断与处置

- 控制面或网络面多节点异常：暂停单节点修复，升级平台负责人并保护控制面。
- kubelet/运行时异常：收集日志后按标准服务恢复流程处理；服务重启需要审批。
- 资源压力：转入对应 CPU、内存、磁盘 SOP，避免只清除 NotReady 表象。
- 节点不可恢复：确认 PDB、存储挂载和副本安全后执行受控 cordon/drain/替换。

## 恢复验证与升级

节点 Ready 连续稳定两个窗口，租约持续更新，系统 pod 正常，业务副本、网络和存储无异常。控制面异常、超过一个节点、关键服务无冗余、需 drain/reboot/替换节点时立即升级。

## 安全边界

禁止未审批删除 Node 对象、强制 drain、忽略 PDB、批量重启 kubelet 或重启节点。任何迁移必须确认本地盘数据、DaemonSet、PDB 和回滚方案。
