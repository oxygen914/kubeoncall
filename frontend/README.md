# KubeOnCall Console

KubeOnCall 的 Web 控制台前端(React + TypeScript + Vite)。本目录当前包含两套并存的前端:

- **新 SPA(本迭代交付)**:React + Vite,入口 `index.html` → `src/main.tsx`。
- **旧的原生 JS 调试控制台(保留作回滚路径)**:位于 `src/index.html` + `src/app.js` + `src/styles.css`,由 `scripts/build.mjs` 构建为静态产物。

旧控制台的 `src/index.html` 与新 Vite 入口 `index.html` **不冲突**:Vite 只认根目录的 `index.html`,旧文件仅在运行遗留构建脚本时使用。请勿删除或修改旧文件及 `scripts/build.mjs`、`test/console-contract.test.mjs`。

## 环境要求

- Node.js >= 20
- 后端 Spring Boot 默认监听 `127.0.0.1:8080`,鉴权使用 HttpOnly Cookie(`KOC_SESSION`)+ CSRF(`KOC_CSRF` 可读 cookie,通过 `X-CSRF-Token` 头回传)。所有请求带 `credentials: "include"`。

## 常用命令

```bash
# 安装依赖(锁文件存在时用 ci)
npm ci
# 没有 lockfile 时
npm install

# 本地开发(默认端口 5173,自动代理 /api 与 /config 到 127.0.0.1:8080)
npm run dev

# 类型检查
npm run typecheck

# 生产构建
npm run build

# 预览构建产物
npm run preview

# 单元/组件测试(Vitest + Testing Library)
npm run test
npm run test:watch

# 代码风格
npm run lint
npm run format

# 端到端测试(Playwright,会自动启动 dev server)
npm run e2e
```

## 架构要点

- **API 层**:`src/api/client.ts` 是唯一的 fetch 包装,自动注入 `X-Request-Id`、`X-CSRF-Token`(非 GET)、`credentials: "include"`,并解析统一信封 `{data, meta}` / `{error, meta}`。页面/组件不得直接调用 `fetch`。
- **运行时配置**:通过 `/config/runtime.json` 注入,加载失败回落到同源安全默认值。
- **会话**:`src/features/auth/SessionProvider.tsx` 维护当前会话;`AuthBoundary` 守护受保护路由,`returnTo` 仅允许站内相对路径以防开放重定向。
- **错误**:`src/api/errors.ts` 的 `ApiError` 携带 `code`/`requestId`/`status`/`fieldErrors`/`retryable`。`ErrorBoundary` 作为根渲染错误兜底。
- **设计令牌**:`src/styles/tokens.css`,暗色主题通过 `prefers-color-scheme` 自动切换。

## 回滚

如需回滚到旧控制台,保留 `src/index.html`、`src/app.js`、`src/styles.css`、`scripts/build.mjs`、`test/console-contract.test.mjs` 即可,运行 `node scripts/build.mjs` 产出 `dist/`。
