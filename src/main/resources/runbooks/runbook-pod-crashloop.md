---
runbookId: runbook-pod-crashloop
title: Kubernetes Pod CrashLoopBackOff 处置 SOP
category: k8s-pod
owner: app
version: v1
document_type: runbook
source_type: runbook
---
# Kubernetes Pod CrashLoopBackOff 处置 SOP

## 适用范围与目标

适用于容器反复退出并进入 CrashLoopBackOff。目标是通过退出码、上一次容器日志和事件区分配置、依赖、探针、资源或程序缺陷，并恢复稳定副本。

## 只读取证

1. 查看 pod 状态、容器退出码、reason、restartCount、events、owner reference 和所在节点。
2. 获取当前及 `--previous` 日志，记录首次失败时间、堆栈、依赖地址和探针错误；避免只看最新一次启动。
3. 对比健康副本的镜像 digest、环境变量来源、ConfigMap/Secret resourceVersion、资源限制和探针配置。
4. 关联发布历史、依赖可用性、证书到期和节点异常。

## 判断与处置

- 新版本缺陷或配置不兼容：优先按发布系统回滚到已验证版本，需 owner 审批。
- 依赖不可用：保护依赖并限制重试风暴，不通过延长探针永久掩盖失败。
- 探针参数不合理：先确认应用真实启动时长，灰度调整一个工作负载。
- 资源退出：OOM 转入 Pod OOM SOP；节点资源异常转入节点 SOP。

## 恢复验证与升级

确认期望副本全部 Ready，重启计数不再增长，错误率、延迟和依赖负载恢复，回滚/变更记录完整。关键服务无健康副本、持续重试冲击依赖、涉及 Secret 或无法获得 previous 日志时立即升级。

## 安全边界

禁止直接编辑线上 pod、循环删除 pod、跳过发布系统、输出 Secret 内容或永久关闭探针。所有变更必须作用于声明式工作负载并可回滚。
