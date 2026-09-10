# 1.0.10：熄屏重试与后台恢复

## 日志结论

用户提供的日志：23:34:53 视频创建返回 503 `video_queue_full`；下一次执行直到
次日 07:12:31，07:12:38 才收到成功创建的远端 ID。因此夜间停止的是本地重试，
不能认为视频整夜都在服务器上生成。原日志缺少系统状态，不能认定是 Doze、
厂商杀进程或 dataSync 配额中的某一种。

1.0.9 已有独立前台服务、WorkManager 与唤醒锁，但没有电池优化放行入口，
也没有可在 idle 期间触发的独立恢复唤醒。前台服务和普通唤醒锁本身不豁免 Doze 网络限制。

## 改动

- 设置页和有活动任务的队列页显示电池优化、通知状态；仅用户点击后请求系统放行。
  荣耀 / 华为用户同时提示检查应用启动管理。返回页面后刷新实际权限状态。
- 持久队列增加 `setAndAllowWhileIdle` 非精确恢复闹钟，不申请精确闹钟权限。
  正常前台服务继续每 30 秒查询；恢复闹钟只是兜底，不能承诺秒级唤醒。
  重复 kick 不会不断推迟已有闹钟；空队列会撤销闹钟。
- 已获得电池优化豁免且未触发 dataSync 超时的应用可从恢复唤醒重启服务；
  其他情况使用独立、合并的 WorkManager 唤醒任务，避免 KEEP 被旧延迟任务挡住，
  也避免 REPLACE 取消正在发送的 POST。
- 重启和应用升级后恢复调度；不从 BOOT_COMPLETED 启动 Android 15 禁止的 dataSync 服务。
- 服务保留 30 秒心跳，使用每次最多 2 分钟、仅服务存活期间续期的部分唤醒锁；
  任务完成或服务销毁时释放。dataSync 超时后保留标记，直到可见 Activity 重置预算；
  不循环重启规避系统 6 小时限制。
- 通知显示重试/查询计划时间；日志记录重试上限、停止原因，以及恢复时的迟到秒数、
  电池优化、idle、省电模式、厂商和 Android 版本。异常终止没有回调时不编造原因。
- 既有 SQLite 检查点、素材链接缓存、远端 ID 与进程互斥继续使用；恢复不会清空 ID。

## 验证

CI 运行 Lint、JVM/Robolectric 测试、主 APK 和仪器测试 APK 构建。
新增回归场景：503 后关闭 Activity 仍重试成功；恢复闹钟不会被重复推迟；
取消后撤销闹钟；放行后的服务恢复保留远端 ID；Android 15 启动限制和超时标记；
长时间迟到的恢复日志。

已通过 [GitHub Actions 34542975830](https://github.com/zjjxwpstcnsm-gif/agnes-studio/actions/runs/34542975830)，
构建提交 `4301479431b8ac0345d323612ed2bf67b01992d6`。

- 53 项测试：0 失败、0 错误、0 跳过。
- Lint：0 错误、20 条警告（依赖版本、KTX 建议、目标 SDK 和未用资源）。
- 主 APK、仪器测试 APK、并存后台版 APK 均编译成功；仪器测试未在设备执行。
- 后台版交付 APK：`AgnesStudio-v1.0.10-background.apk`，22,198,157 字节。
- APK SHA-256：`2b056e1cad97de09642db2957c10331f5f76654aa8bb84d42fa6346abe7a13de`。
- v2 / v3 签名校验通过；重签前后所有应用载荷条目哈希一致。
- 解析最终 APK 确认包名 `com.ppailab.agnesstudio.background`，版本 `1.0.10 (11)`，最低 API 26，目标 API 35。
- 固定证书 SHA-256：`1baf80c86e2a11d37ea8fa93c4b7183cfdd0320dcf38e4dbad650dd3c6c430b9`。
- 私有签名备份名称：`AgnesStudio-background-signing-backup.zip`；已单独保存，不在仓库或 CI 中。
  后续交付必须找回此备份重签，不能临时换证书或把私钥放入公共工作流。

未连接用户真机，不能把 Robolectric 测试等同于荣耀手机整夜熄屏实测。
真机验收：允许后台运行和通知，荣耀启动管理允许三个选项；提交任务后锁屏，
检查创建、重试、轮询日志是否连续。有条件时用 `adb shell dumpsys deviceidle force-idle`
验证，再用 `adb shell dumpsys deviceidle unforce` 退出。强行停止无法保证自动恢复。

## 安装

主包保持 `com.ppailab.agnesstudio`。旧 1.0.9 临时签名私钥未保存，不能用新证书覆盖旧包。
不要通过卸载旧包来解决签名冲突，以免丢失本地记录。

`./gradlew assembleDebug -PbackgroundPreview=true` 生成可并存的
`com.ppailab.agnesstudio.background`（Agnes Studio 后台版）。它的数据独立，需要重新填写 Key；
旧任务和历史留在旧应用。交付的后台版在本地用固定私有签名重签，并单独保存签名材料供后续复用。
签名私钥不能提交到 GitHub、CI Artifact 或缓存；CI 只输出待重签 APK 和公开签名工具。

## 官方依据

- [Doze 对网络和唤醒锁的限制及电池优化豁免](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [后台启动前台服务的限制与豁免](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 15 dataSync 时间限制](https://developer.android.com/develop/background-work/services/fgs/timeout)
