package com.ppailab.agnesstudio.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun BackgroundExecutionCard(compact: Boolean = false) {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    if (compact && unrestricted && notifications) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("后台持续生成", fontWeight = FontWeight.SemiBold)
            Text(
                if (unrestricted) "电池优化已放行" else "电池优化尚未放行，熄屏后重试可能延迟",
                color = if (unrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!compact) Text(
                "允许后台运行后，熄屏期间可继续上传、重试和查询视频。仅有未完成任务时保持执行，可能增加耗电。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { requestBackgroundExecution(context, unrestricted) }) {
                    Text(if (unrestricted) "电池设置" else "允许后台运行")
                }
                if (!notifications) TextButton(onClick = {
                    openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("开启任务通知") }
            }
            if (!compact) {
                Text(if (notifications) "任务通知已开启" else "任务通知未开启，无法在通知栏查看执行状态")
                val manufacturer = Build.MANUFACTURER.lowercase()
                Text(
                    if (manufacturer.contains("honor") || manufacturer.contains("huawei")) {
                        "荣耀 / 华为：还需在系统「应用启动管理」找到 Agnes Studio，关闭自动管理，允许自启动、关联启动和后台活动。可将应用锁定在最近任务中。"
                    } else {
                        "如果系统另有应用启动或后台活动管理，请允许 Agnes Studio 自启动和后台运行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { openSystemSettings(context, appSettingsIntent(context)) }) { Text("打开应用系统设置") }
                Text(
                    "系统强行停止、禁止后台联网或后台运行额度耗尽时，执行仍可能延迟；重新打开会恢复已保存的进度。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// Uninterrupted, user-requested generation/retry is a core app function and the
// provider has no push callback to this device. Ask only on an explicit tap.
@SuppressLint("BatteryLife")
private fun requestBackgroundExecution(context: Context, unrestricted: Boolean) {
    val intent = if (unrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    openSystemSettings(context, intent)
}

private fun appSettingsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"),
)

private fun openSystemSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(appSettingsIntent(context))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "请在系统设置中打开 Agnes Studio 的电池与后台运行设置", Toast.LENGTH_LONG).show()
        }
    }
}
