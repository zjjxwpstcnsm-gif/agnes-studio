# 1.0.8 候选修复：视频素材上传后的后台取消

状态：源码改动和回归用例已完成，尚未编译、运行测试或生成新版 APK。

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
- 视频限流槽位移到素材准备完成、真正发送 POST 前；结果轮询仍为 30 秒。

## 回归用例（已添加，尚未执行）

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

## 当前构建障碍

- 原 Android SDK、Gradle 缓存已随环境维护清理。
- 下载工具链的网络审批被取消，无法取得构建依赖。本次没有绕过该限制。
- 现有可恢复文件中未找到 1.0.7 的签名私钥。旧证书 SHA-256 为
  `a59fefc73e88b797d3b3b9a2d82bcb63731ea57ac3ffb1240926f0f8134f2036`。
- 旧 APK 不含可用于重新签名的私钥；没有同一签名密钥就不能覆盖安装并继承旧应用数据。
  可选择恢复原密钥，或者经用户确认后改成与旧应用并存的新安装包；不要直接卸载旧应用。

本次未进行真实图床上传或 Agnes 视频生成调用。对于服务器已接受 POST 但响应完全丢失的情形，
仍需服务端幂等支持或任务查询契约才能保证绝不重复创建，不能把本次检查点修复宣称为完整的远端幂等保证。
