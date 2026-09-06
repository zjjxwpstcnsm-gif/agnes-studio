# 1.0.9：切换其他 App 后继续提交视频

## 故障依据

用户日志：15:16:16 POST 创建视频；15:16:18 普通 WorkManager 被停止（`code=0`）；
15:16:49 恢复后进入本地限流，但直到 15:21:18 才再次 POST。
`code=0` 本身不能确认厂商杀进程或某个具体配额；能够确认的是原请求被取消，恢复调度明显延迟。

旧实现只有普通 Worker，没有前台服务；且长时间挂在 Worker 的等待循环里。
WorkManager 调度不承诺秒级执行。Android 16 下连使用前台服务的长 Worker 也受 JobScheduler 配额约束。

## 改动

- 点击生成/重试，或返回有未完成任务的应用时，从可见界面直接启动 `dataSync` 前台服务。
- 服务与 Activity 生命周期分离；切后台不取消上传或创建请求。创建、限流等待、30 秒轮询和下载由同一服务继续执行。
- 声明前台服务类型、对应权限及通知权限；通知可打开应用查看/取消任务，通知权限拒绝不阻止提交。
- 有限时长的部分唤醒锁保持 CPU 可运行；队列完成、取消、服务销毁后释放。
- 不再因固定 8 分钟工作窗口把正常服务任务交回 WorkManager；处理 Android 15 的 dataSync 超时回调，及时停止并保留恢复记录。
- 新任务/取消唤醒正在等待的处理器，避免它先睡完上一条任务的等待周期。
- WorkManager 只处理已到期的恢复任务，不等待前台服务持有的锁，不占着 Worker 等下一个轮询时间；已有互斥锁和视频 ID 检查点继续防止本地并发重复创建。
- 未创建成功的任务中断恢复不再额外等 30 秒；仍遵守实际的 60 秒滑动限流窗口。已有远端 ID 的查询保留 30 秒下限。

## 验证

2026-09-06 已通过 [GitHub Actions](https://github.com/zjjxwpstcnsm-gif/agnes-studio/actions/runs/34024760787)，
构建代码提交 `47e5d5c4bae3a786d9b18ae8de7b2a224a5fa715`。

- 47 项 JVM/Robolectric 测试全部通过：0 失败、0 错误、0 跳过。
- Lint：0 错误，12 条警告。
- 主 APK 与仪器测试 APK 编译成功；仪器测试 APK 未在真机/模拟器执行。
- APK：`com.ppailab.agnesstudio`，版本 `1.0.9 (10)`，22,162,588 字节。
- APK SHA-256：`9f7525fc4696d45146dc72ef3367a280da1da9e43929749495d8ff92e0631da2`。
- APK v2 签名验证通过；下载后的 Artifact ZIP 与 APK 均已核对 SHA-256。

CI 执行 `lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest`。
新增测试覆盖：切后台期间 HTTP 仍完成并保存 ID；恢复 Worker 不重复创建；通知和唤醒锁生命周期；
空队列不启动服务；服务执行超过 8 分钟仍持续；新提交即时唤醒；中断创建恢复与轮询使用不同等待规则。

本次未连接用户手机，未发起真实 Agnes 生成。Robolectric 生命周期模拟不能代替手机厂商系统上的真机验收。
安装后可按原问题复测：提交视频后立即切换其他 App，5 分钟后返回，检查创建响应和每次轮询的日志时间。

## 安装和平台边界

不能从旧 APK 还原签名私钥；1.0.8 的 GitHub Actions 临时调试签名也没有被保存。
本次 1.0.9 APK 证书 SHA-256 为 `1bca3f1cf57ae9468ddb32a8eb21738c0935372ec72d52613197872dc9274a41`，
与 1.0.8 CI 包的 `a59c8baad0522442a7fa4c4cbed2a3ffddefe502a2d19ecc453b9de8687d22e0` 不同，
因此不能覆盖安装到该旧版本上；不能通过卸载旧应用来冒险丢失历史数据。
用户主动在系统中“强行停止”或厂商禁止后台运行时，应用仍不能保证继续执行；已保存的链接和远端 ID 在下次打开后复用。
服务端接受 POST 但响应完全丢失的情形仍需要服务端幂等/查询契约，客户端不能单独保证远端绝不重复。

## 官方依据

- [WorkManager 长时间任务与 Android 16 配额](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [前台服务启动要求](https://developer.android.com/develop/background-work/services/fgs/launch)
- [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)
