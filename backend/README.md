# KubeOnCall Backend

Spring Boot 后端，包含告警接入、诊断工作流、RAG、记忆、Skill、MCP、审批和审计能力。

## 开发

```bash
./mvnw --batch-mode --no-transfer-progress verify
./mvnw spring-boot:run
```

配置默认值位于 `src/main/resources/application.yml`。本地依赖、环境变量和完整运行方式见仓库根目录文档。
