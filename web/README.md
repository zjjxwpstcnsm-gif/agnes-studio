# Agnes Studio Web

无需构建框架的浏览器客户端，与根目录 Android 应用并存。网站使用相对资源路径和 hash 导航，适配 GitHub Pages 项目子路径与移动设备。

## 打开网页

部署地址：<https://zjjxwpstcnsm-gif.github.io/agnes-studio/>

1. 进入「连接与设置」，填写自己的 Agnes API Key
2. 确认 API Base URL 指向你信任的、允许浏览器跨域调用的 HTTPS 服务
3. 应用连接。Key 仅保留在当前页面内存中，刷新后需重新填写
4. 通过创作工作台提交图片/视频，或进入灵感对话进行多轮聊天

**重要：GitHub Pages 只托管静态前端，不是 API 代理。** 2026-10-01 的无凭据 OPTIONS 检查发现，`https://apihub.agnes-ai.com` 的相关接口返回 `Access-Control-Allow-Headers: *`，没有显式允许 `Authorization`；浏览器不能以通配符代替该请求头授权。官方地址的直接 Bearer 调用可能因此被 CORS 拦截。这与 Key 是否有效、余额是否充足不同。需要 Agnes 正确配置 CORS，或你自己部署/信任的 HTTPS API 网关。不要将 Key 发给陌生的“免费跨域代理”，也不要关闭浏览器安全保护。

一个兼容网关必须：

- 实现相同的 `/v1/chat/completions`、`/v1/images/generations`、`/v1/videos`、`/agnesapi` 路由；URL 可带前缀
- 允许站点来源、`GET, POST, OPTIONS`，且在 CORS 允许请求头中**明确列出 `Authorization, Content-Type`**
- 将成功和错误响应都附带正确的 `Access-Control-Allow-Origin`
- 支持透传 SSE，并且不缓冲整个聊天回复
- 不记录 API Key；使用 HTTPS，避免请求重定向

“检查连接”只执行 `GET /v1/models`，不会生成内容；部分兼容服务可能不实现该接口。

## 功能

- 聊天：3.0 Flash、多轮与独立会话参数、流式/非流式、停止生成保留已有内容、Thinking、公开图片 URL、工具调用人工回填
- 图片：2.5 Flash、1K–4K、8 种比例、URL/Base64、多个 HTTPS 参考图
- 视频：2.5 Flash/完整模型、4–12 秒、text/keyframe/reference、公开参考图片/音频、完整模型的参考视频参数
- 任务：IndexedDB 持久化、20 个待处理任务上限、免费档分桶限流、任务筛选、参数复用、结果预览与原链接、导出本机数据
- 视频查询：获取远端 ID 后，每次查询至少间隔 30 秒；支持暂停和恢复
- 高级 JSON 的核心参数由可视化字段覆盖，避免隐藏参数绕过校验
- 公开素材 URL 直接发送到 API，不自动上传本地文件

## 与 Android 版的边界

- 浏览器关闭/休眠会暂停任务；没有前台服务、WorkManager 或系统级后台执行
- Key 不持久化、不写入日志或导出，不使用 localStorage/sessionStorage 保存
- 尚未收到远端 ID 的 POST 若网络中断、取消或刷新，可能已被服务器接受并计费。网页会标记“提交结果待确认”，**不会自动重发**；先检查服务端记录再决定是否重新生成
- 仅明确的 429 拒绝允许有限重试，并尊重 Retry-After；视频 GET 查询允许有限退避重试
- “停止跟踪”不等于取消服务端任务，不保证退款
- 结果只存链接或 API 返回的图片 Base64；远端媒体可能过期。请及时打开原图/视频另存
- 本机数据与 Android 不自动同步；导出为 JSON 备份，目前不提供跨端导入
- 当前不自动上传本地媒体、不自动执行模型工具，也不内置 API Key
- 默认使用免费套餐频率作为保守本地限制；服务端实际配额仍以账户为准

## 本地开发与验证

Node.js 22 或更新版本：

```sh
cd web
npm ci
npm run check
npm test
npx playwright install chromium
npm run test:e2e
npm run dev
```

打开 `http://localhost:4173`。已有系统 Chromium 时可通过 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` 指定测试浏览器。

```sh
npm run build
node scripts/serve.mjs --dist
```

发布目录是 `web/dist/`；不包含服务端、API Key 或第三方运行时依赖。Playwright 是开发依赖。测试使用拦截的模拟 API 响应，不消耗真实 Agnes 额度；通过模拟测试不代表真实账户生成权限已验证。

## GitHub Pages

仓库 Settings → Pages → Build and deployment → Source 选择 **GitHub Actions**。不要把 API Key 添加到 Pages 源码或构建环境变量。

`.github/workflows/web-pages.yml` 在 PR 中执行语法、单元与浏览器测试，并构建静态站点。main 的网页变更通过全部检查后，用官方 Pages artifact / deployment actions 发布。只授予发布 job `pages: write`、`id-token: write`；构建 job 只有仓库读取权限。

Android 工程及原来的 Android CI 保持独立。
