# KubeOnCall 开发贡献指南

## 环境要求

- JDK 17
- Maven Wrapper（使用 `./mvnw`，不依赖系统 Maven 版本）
- Docker Compose（仅运行真实依赖集成测试时需要）

## 提交前检查

先自动修复格式：

```bash
./mvnw spotless:apply
```

再执行完整的本地门禁：

```bash
./mvnw verify
```

该命令会校验 JDK、Maven 版本、Java 和文本格式、基础 Checkstyle 规则，并运行默认单元测试。

运行真实依赖集成测试：

```bash
docker compose up -d redis elasticsearch minio minio-init
./mvnw -Pintegration-test verify
```

## 代码约定

- 新增 Spring Bean 使用构造器注入；仅有一个构造器时不写 `@Autowired`。
- 新增 DTO、事件和值对象优先使用 record 和不可变集合。
- 不返回 `null` 集合；必需依赖不以 `null` 表达未启用状态。
- 不新增通配符 import、无用 import 或无说明的异常吞噬。
- Controller 只处理 HTTP 协议和参数校验；业务编排位于 application service。
- 将格式化、包迁移和业务逻辑修改拆为独立提交。

安装与配置入口见[文档索引](docs/README.md)，模块边界见[项目架构](项目架构.md)，当前重构范围和验收状态见[当前重构进度](重构计划/当前重构进度.md)。
