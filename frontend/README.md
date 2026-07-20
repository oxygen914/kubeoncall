# KubeOnCall Console

独立的 KubeOnCall 数据调试控制台。页面通过 Bearer Token 调用后端 API，Token 和请求历史只保存在页面内存。

## 本地验证

```bash
npm test
npm run build
```

推荐从仓库根目录通过 Docker Compose 启动。控制台默认访问 `http://127.0.0.1:8081`，Nginx 将 `/api` 和 `/actuator` 转发到后端服务。

如果单独托管 `src/` 或 `dist/`，可在页面的 `API Base URL` 中填写后端地址。后端默认仅允许来自 `localhost:8081` 和 `127.0.0.1:8081` 的跨域请求；其他来源通过 `KUBEONCALL_CORS_ALLOWED_ORIGINS` 显式配置。
