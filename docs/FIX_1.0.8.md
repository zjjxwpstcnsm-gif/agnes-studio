# 1.0.8 候选修复：视频素材上传后的后台取消

状态：2026-09-06 已通过 [GitHub Actions 验证](https://github.com/zjjxwpstcnsm-gif/agnes-studio/actions/runs/34012525330)，对应代码提交 `d73157308e6e23e9b5b3f96046e8c36f7e0d548e`。

- Lint：0 个错误。
- 单元/Robolectric 测试：42 条通过，0 失败、0 错误、0 跳过。
- 主 APK 和仪器测试 APK：编译成功；仪器测试未在模拟器或真机上执行。
- 主 APK：`1.0.8 (9)`，SHA-256 为 `cc5715c5edf375b7e6e89f2ef78f51e5bae6f992aa79862d96dd6d8cdac48d1e`。
- 本版本未进行真实图床上传和 Agnes 视频生成调用。

## 对应故障

1.0.7 在 `withContext(IO)` 内同步上传并记录成功，但在返回后才保存 URL。
父协程在上传途中取消时，HTTP 仍可能继续执行；上传结束后返回值被取消机制丢弃，
缓存和视频 POST 均不会执行。队列又把取消异常转成终态失败，导致手动重试重复上传。

## 本次改动

- `CancellableHttp.kt`：用异步 HTTP 回调桥接协程，取消时终止请求；等待回调清理完成后再释放任务执行权。
- `TemporaryMediaUploader.kt`：优先 Uguu；成功回调内先执行持久化检查点，再交付返回值；取消不会触发换源。
- `AgnesApiClient.kt`：视频创建成功后，在返回给协程前保存已知 `video_id`。
- `QueueProcessor.kt`：同一 AppGraph 中的 Worker 共享互斥执行；取消异常单独处理并向上传播；后台暂停保留恢复状态。
- `AppDatabase.kt`：迟到状态更新不得覆盖用户取消；保存视频 ID 时也不复活已取消任务；恢复不覆盖终态。
- `AppViewModel.kt`：用户取消终止实际执行，重新排队等待旧执行完成清理。
- `GenerationQueue.kt`：记录 Worker ID/停止原因；8 分钟工作窗口到期后交回 WorkManager；系统取消不被吞掉。
- 停止原因接口仅在 Android 12 及以上读取，旧系统仍记录 Worker ID 和停止状态；首轮 CI 发现的 API 版本兼容错误已修复。
- 视频限流槽位移到素材准备完成、真正发送 POST 前；结果轮询仍为 30 秒。

## 回归用例（已执行通过）

- 上传过程中取消父协程：验证 HTTP Call 被取消，不再尝试下一图床。
- 上传成功并写入缓存后、结果交付前取消：验证缓存存在，恢复后不再上传。
- 停止旧 Worker 并重建数据库连接和 QueueProcessor：验证复用持久链接，继续视频创建及结果轮询。
- 两个 Worker 同时处理：验证同一任务仅有一个上传请求；用户取消不会被迟到失败覆盖。
- 收到创建结果后、调用方恢复前取消：验证 `video_id` 已落盘。
- 用户取消与远端成功响应竞争：保持已取消状态，重新排队时复用已知远端 ID。

在可用的 JDK 17 / Android SDK 35 环境执行：

```bash
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

项目仓库为 [`zjjxwpstcnsm-gif/agnes-studio`](https://github.com/zjjxwpstcnsm-gif/agnes-studio)，由用户设置为公开。`.github/workflows/android.yml` 已配置自动构建、手动触发和测试报告上传；具体结果以该次 Actions 运行记录为准，不把源码提交视为验证通过。

## 构建与安装限制

- 本地 Android 工具链不可用，本次在新建仓库的 GitHub Actions 中完成了构建和测试。
- 现有可恢复文件中未找到 1.0.7 的签名私钥。旧证书 SHA-256 为
  `a59fefc73e88b797d3b3b9a2d82bcb63731ea57ac3ffb1240926f0f8134f2036`。
- 本次 CI 调试包证书 SHA-256 为
  `a59c8baad0522442a7fa4c4cbed2a3ffddefe502a2d19ecc453b9de8687d22e0`，与旧版不同。
- 旧 APK 不含可用于重新签名的私钥；没有同一签名密钥就不能覆盖安装并继承旧应用数据。
  可选择恢复原密钥，或者经用户确认后改成与旧应用并存的新安装包；不要直接卸载旧应用。

本次未进行真实图床上传或 Agnes 视频生成调用。对于服务器已接受 POST 但响应完全丢失的情形，
仍需服务端幂等支持或任务查询契约才能保证绝不重复创建，不能把本次检查点修复宣称为完整的远端幂等保证。
