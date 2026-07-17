# CI/CD ChangeEvent 接入

KubeOnCall 接收入口：

```text
POST /api/integrations/change-events/{provider}
```

`provider` 支持 `generic`、`github`、`gitlab`、`jenkins`、`argocd`。默认使用 Bearer；配置对应 Provider Secret 后，GitHub 使用 `X-Hub-Signature-256`，GitLab/Jenkins/Argo CD 使用各自 Token header。

`publish-change-event.sh` 支持以下鉴权变量：

- 通用 Bearer：`KUBEONCALL_CHANGE_EVENT_TOKEN`
- GitHub HMAC：`KUBEONCALL_CHANGE_EVENT_GITHUB_SECRET`
- GitLab：`KUBEONCALL_CHANGE_EVENT_GITLAB_TOKEN`
- Jenkins：`KUBEONCALL_CHANGE_EVENT_JENKINS_TOKEN`
- Argo CD：`KUBEONCALL_CHANGE_EVENT_ARGOCD_TOKEN`

服务端配置了 Provider Secret 时，投递端必须设置对应 Provider 变量；脚本会同时保留 Bearer header，便于同一资产兼容回退配置。GitHub HMAC 模式必须传入文件，不能从标准输入投递，因为签名必须与请求原始字节完全一致。

## 通用发布脚本

```bash
export KUBEONCALL_CHANGE_EVENT_URL=https://kubeoncall.example.com
export KUBEONCALL_CHANGE_EVENT_TOKEN='从 CI Secret 注入'
export KUBEONCALL_CHANGE_EVENT_PROVIDER=generic
export KUBEONCALL_CHANGE_EVENT_TYPE=deployment

./scripts/publish-change-event.sh payload.json
```

不要把 Token、Webhook Secret 或内部地址提交到仓库。生产部署应从 Kubernetes Secret、GitHub Actions Secret、GitLab CI Variable、Jenkins Credential 或 Argo CD Secret 注入。

## Provider 示例

- `github-actions.yml.example`：部署任务完成后使用 GitHub Actions Secret 投递。
- `gitlab-ci.yml.example`：GitLab deploy stage 投递。
- `Jenkinsfile.example`：Jenkins post-success 投递。
- `argocd-notifications-cm.yaml.example`：Argo CD Notifications Webhook 模板。

真实 Provider Webhook 若直接调用 KubeOnCall，应优先使用原生签名或 Token；CI 脚本主动投递时使用统一 Bearer。
