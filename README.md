# Agnes Studio for Android

一个原生 Android 全模态客户端：填入 Agnes API Key 后即可进行多轮文本对话、图片生成/编辑和视频生成。界面与任务状态围绕“提交后发生了什么”设计，限流、排队、重试和远端处理进度均会直接显示。

## 能力

### 文本 · `agnes-2.5-flash`

- 多轮对话与本地历史记录
- OpenAI Chat Completions SSE 流式输出
- Thinking 独立折叠展示；兼容独立 reasoning 字段和 `<think>` 标签
- Tool Call 名称、参数与等待状态卡片；由用户填写工具执行结果后继续对话
- 图片视觉输入（本地图片转 Data URI，或公开 HTTPS URL）
- 可设置模型、System Prompt、temperature、top_p、max_tokens、stream、thinking、tools、tool_choice
- 高级 JSON 可透传未来新增参数，核心字段仍由可视化控件校验并覆盖
- “停止生成”保留已收到内容，不丢失半截答案
- 流式生成时可自由浏览历史；上滑后暂停自动跟随，并可一键回到底部
- 每条消息独立保存展开状态；超长正文收起后切换为独立的 6 行/260 字短预览，并自动定位回该消息
- 每个会话独立保存模型、System Prompt、temperature、top_p、max_tokens、Thinking、Tools 与高级 JSON；切换回来即可沿用

### 图片 · `agnes-image-2.5-flash`

- 文生图、单图/多图参考编辑
- 1K / 2K / 3K / 4K
- 1:1、3:4、4:3、16:9、9:16、2:3、3:2、21:9
- URL / Base64 响应
- 参考图按接口要求写入 `extra_body.image`
- 生成结果在任务卡片中直接预览、分享或保存

### 视频 · `agnes-video-2.5-flash`

- text、keyframe、reference 三种模式
- 4–12 秒、720P、六种画幅、seed
- 首帧/尾帧、最多 5 张参考图、最多 3 段参考音频
- 创建后每 30 秒轮询一次 `video_id`，任务卡显示下次查询倒计时、进度和内嵌播放器
- 可选完整模型 `agnes-video-2.5`：开放 960P / 2K 和参考视频参数
- 参考视频可逐项设置 `start_seconds` 与 `require_audio`
- 免费 Flash 的限制会在发送前拦截并给出可操作提示

> Agnes 视频接口要求参考素材是服务器可访问的 HTTPS URL。应用支持直接填写 URL；本地视频/音频/图片可选择启用公开素材中转池。中转池优先使用 Uguu（约 3 小时），失败再尝试 Litterbox（约 1 小时）。上传响应回调会先把直链与过期时间写回持久任务，再通知调用方；后台取消不会丢弃已保存的链接。视频创建失败后的自动/手动重试直接复用，只有链接临近过期才重新上传。该选项默认关闭，开启前会明确提示素材将发送至第三方临时文件服务。

## 队列与可靠性

- 图片和视频使用 SQLite 持久队列 + WorkManager；应用退到后台或进程重启后可以恢复
- 队列容量可配置（默认 20），满时在本地拒绝新任务并显示当前容量
- 按套餐和模态使用 60 秒滑动窗口限流；图片还按 1K–4K 分桶
- 支持 Agnes 免费档默认频率，也可选择其他套餐或手动设置 RPM
- 429 尊重 `Retry-After`
- 5xx 响应中只要包含 queue/full/busy/capacity/overload 等信息，即识别为“远端队列满”
- 网络异常、超时、429、远端队列满和可恢复 5xx：指数退避 + 抖动重试
- 400/422 参数错误、401/403 Key/权限错误、额度耗尽、内容策略、素材不可访问：不盲目重试，给出针对性建议
- 任务状态包括：已排队、限流等待、正在发送、服务端生成中、等待重试、完成、失败、取消
- 队列中每条任务均可查看脱敏调用日志：请求参数、HTTP 状态、响应正文、轮询进度和重试原因；长消息与长返回体可逐段展开/收起
- 队列支持全部、进行中、失败和已取消筛选
- 图片页、视频页和队列中的每条生成记录都有“复制提示词”按钮；详细参数快照与任务 JSON 仍可单独查看
- 视频轮询兼容短暂 404、`data` / `result` 包装响应和多种完成 URL 字段；轮询异常重试同样遵守至少 30 秒间隔
- 本地素材一旦中转成功即持久缓存公开 URL；视频创建失败、手动重试或 App 重启恢复时不会重复上传，缓存过期后才自动换源重传

## 安全与隐私

- API Key 通过 Android Keystore 生成的 AES-GCM 密钥加密，密文仅保存在应用私有区
- 禁止应用备份，避免凭据进入云备份
- Key 不写日志、不进入数据库、不包含在源码或 APK 中
- Base URL 强制 HTTPS；应用禁用明文流量
- 对话、队列、附件和生成结果默认只存在本机应用私有目录
- 导入时限制图片 20 MB、音频 100 MB、视频 500 MB，避免大文件耗尽设备内存或存储
- Tool Call 不会被客户端擅自执行，避免模型调用任意本地或外部函数
- 公开素材中转默认关闭；开启后，第三方临时服务会接收文件内容、文件名和上传网络信息，切勿上传敏感素材

## 安装

当前版本为 `1.0.8 (9)`。2026-09-06 的 [GitHub Actions 构建](https://github.com/zjjxwpstcnsm-gif/agnes-studio/actions/runs/34012525330) 已通过 Lint 和全部 42 条单元/Robolectric 测试，并成功编译主 APK 与仪器测试 APK。主安装包位于该次运行的 `AgnesStudio-debug` Artifact 中。尚未进行本版本的真实 Agnes 调用或真机验收。

安装前请先阅读下方签名限制，再按以下方式操作：

1. 在 Android 8.0 或更高版本设备上允许当前文件管理器“安装未知应用”。
2. 打开 APK 并安装。
3. 进入“设置”，粘贴 API Key，点击“保存 Key”；可选点击“测试连接”。
4. 设置页标题下应显示 `Agnes Studio 1.0.8 (9)`。

覆盖安装必须复用旧版签名密钥。本次恢复的源码不包含旧版 `debug.keystore`，且已确认本次 CI 安装包的签名与旧版不同，因此不能覆盖旧 APK。请勿为解决签名冲突而直接卸载旧版，否则会删除其私有区内的对话、队列与素材。

调试签名适合个人试用。正式分发前应使用你自己的发布证书构建 release APK/AAB。

## 从源码构建

要求：JDK 17、Android SDK 35、Build Tools 35.0.0。

本项目作为独立的 `agnes-studio` 仓库维护。请用 Android Studio 打开项目根目录，或在该目录执行以下命令。`.github/workflows/android.yml` 负责 Android CI。

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 的 `Android CI` 工作流会执行 Lint、单元/恢复测试，并编译主 APK 与仪器测试 APK。构建成功后在该次运行的 Artifacts 中下载 `AgnesStudio-debug`，测试报告位于 `AgnesStudio-verification`。编译仪器测试 APK 不等于已在模拟器或真机上运行测试。

## 接口与参数

完整映射见 [`docs/API_MATRIX.md`](docs/API_MATRIX.md)，内部结构见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

官方资料：

- [Agnes 2.5 Flash](https://wiki.agnes-ai.com/en/docs/agnes-25-flash)
- [Agnes Image 2.5 Flash](https://www.agnes-ai.com/zh-Hans/docs/agnes-image-25-flash)
- [Agnes Video 2.5 Flash](https://www.agnes-ai.com/zh-Hans/docs/agnes-video-25-flash)
- [Agnes Video 2.5](https://wiki.agnes-ai.com/zh-Hans/docs/agnes-video-25)
- [套餐与限流](https://wiki.agnes-ai.com/zh-Hans/docs/tokenplan)

## 当前边界

- 本项目未内置任何 API Key，也不会代用户创建 Agnes 账号。
- Agnes 没有面向移动设备的直接本地文件上传字段；参考视频必须先成为公开 URL。临时中转是显式可选功能。
- Tool Call 目前采用安全的“人工执行并回填结果”模式；若要自动执行具体工具，需要为每个工具单独实现权限、参数和确认流程。
- 高级 JSON 为接口演进留出兼容入口；错误字段仍可能被服务器拒绝，并会以参数错误呈现。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
