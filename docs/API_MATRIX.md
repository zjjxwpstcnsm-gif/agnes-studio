# Agnes API 映射

以下为应用 1.0.8 使用的接口与字段。本版本已通过 CI 编译、Lint 和 42 条单元/Robolectric 测试，尚未完成真实服务调用验收。界面字段会覆盖高级 JSON 中同名的核心字段，避免原始 JSON 绕过必要校验。

## 文本

| 项目 | 值 |
|---|---|
| Endpoint | `POST /v1/chat/completions` |
| 默认模型 | `agnes-2.5-flash` |
| 历史 | `messages[]`，含 system/user/assistant/tool |
| 采样 | `temperature`、`top_p`、`max_tokens` |
| 流式 | `stream`；解析标准 SSE `data:` 事件与 `[DONE]` |
| Thinking | `chat_template_kwargs.enable_thinking`；显示 `reasoning_content` / `reasoning` / `thinking` / `<think>` |
| 工具 | `tools`、`tool_choice`、assistant `tool_calls`、tool `tool_call_id` |
| 图片 | message content 中的 `image_url`，支持 HTTPS URL 和 Data URI |
| 扩展 | 高级 JSON 对象 |

客户端最大输出令牌校验范围为 1–65,536；temperature 为 0–2；top_p 为 0–1。

模型、System Prompt、采样参数、Thinking、Tools、Tool Choice 与高级 JSON 会作为一个整体保存在当前会话。切换历史会话时恢复该快照，继续请求不会误用其他会话的参数。

## 图片

| 项目 | 值 |
|---|---|
| Endpoint | `POST /v1/images/generations` |
| 默认模型 | `agnes-image-2.5-flash` |
| 必填 | `model`、`prompt`、`size`、`ratio` |
| 分辨率 | `1K`、`2K`、`3K`、`4K` |
| 比例 | `1:1`、`3:4`、`4:3`、`16:9`、`9:16`、`2:3`、`3:2`、`21:9` |
| 参考图 | `extra_body.image[]` |
| 响应格式 | `extra_body.response_format`: `url` / `b64_json` |
| 纯文生图 Base64 | 同时写入 `return_base64: true` |
| 扩展 | 高级 JSON 对象；其中 `extra_body` 会安全合并 |

本地参考图片以 Data URI 发送，不经过第三方图床。

## 视频

创建任务：

```text
POST /v1/videos
```

查询任务：

```text
GET /agnesapi?video_id={id}&model_name={model}
```

| 字段 | Flash 2.5 | 完整 2.5 |
|---|---|---|
| `seconds` | 字符串 `"4"`–`"12"` | 字符串 `"4"`–`"12"` |
| `mode` | text / keyframe / reference | text / keyframe / reference |
| `size` | 720P | 720P / 960P / 2K |
| `aspect_ratio` | 21:9 / 16:9 / 4:3 / 1:1 / 3:4 / 9:16 | 同左 |
| `seed` | 可选 | 可选 |
| `n` | 固定 1 | 固定 1 |
| `first_frame` / `last_frame` | keyframe | keyframe |
| `images[]` | reference，最多 5 张 | reference |
| `audios[]` | reference，最多 3 段 | reference |
| `videos[]` | 不支持，发送前拦截 | reference；对象含 `url`、`start_seconds`、`require_audio` |

创建成功后固定每 30 秒查询一次；轮询网络异常或返回结果尚未同步时，重试间隔也不会短于 30 秒。轮询到成功状态后读取 `metadata.url`，下载到应用私有目录并在任务卡片内播放。

素材处理遵循以下规则：纯文本模式不依赖素材中转；公开 HTTPS URL 会直接传给 Agnes；只有手机本地素材需要显式启用公开素材中转池。中转池先尝试 Uguu（约 3 小时），失败后自动尝试 Litterbox（约 1 小时）；全部失败才交给任务队列退避重试。任一服务上传成功后，直链与过期时间会在响应回调中写回任务的 `spec_json`，早于协程返回；此后视频创建失败、手动重试或进程恢复都直接复用，只有进入过期安全窗口才重新上传。中转关闭时，本地素材会在入队前被拦截并给出说明，不会产生必然失败的后台任务。

## 默认免费档限流

| 分桶 | 本地默认 RPM |
|---|---:|
| 文本 | 20 |
| 图片 1K | 20 |
| 图片 2K | 10 |
| 图片 3K | 1 |
| 图片 4K | 1 |
| 视频创建 | 1 |

这些值是客户端保护阈值，不代表服务端保证的吞吐量。服务端仍可能根据账户、区域或容量返回 429/5xx，客户端会按响应动态退避。

每个图片/视频任务会把入队时的 `ImageTaskSpec` 或 `VideoTaskSpec` 完整保存在 `spec_json`。生成记录中的“生成设置”可查看结构化字段、参考素材和原始任务 JSON；卡片及设置弹窗的复制操作只复制提示词正文。
