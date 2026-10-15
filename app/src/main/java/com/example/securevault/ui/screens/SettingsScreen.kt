package com.example.securevault.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.securevault.util.AppLock
import com.example.securevault.util.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(masterPassword: String, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var askExportPassword by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var onRestoredPending by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在加密导出备份…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { BackupManager.export(context, masterPassword, it) }
                        ?: error("无法写入所选位置")
                }
            }
            busy = null
            message = result.fold(
                { "备份已导出。恢复时需要输入现在的主密码；以后改了主密码，这份备份仍然要用现在的密码才能解开。" },
                { "导出失败：${it.message}" }
            )
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; confirmRestore = true }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var verifyOn by remember { mutableStateOf(com.example.securevault.util.UserVerifier.isEnabled(context)) }
            Text("安全", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("查看或复制密码前验证指纹")
                    Text("防止手机解锁时被别人拿走翻看", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = verifyOn, onCheckedChange = {
                    verifyOn = it; com.example.securevault.util.UserVerifier.setEnabled(context, it)
                })
            }
            HorizontalDivider()
            DriveBackupSection()
            HorizontalDivider()
            Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
            Text(
                "备份文件已用主密码加密，可以放心存到 Google Drive、网盘或电脑。忘记主密码就无法恢复。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { askExportPassword = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("导出加密备份")
            }
            OutlinedButton(
                onClick = { AppLock.beginExternal(); picker.launch(arrayOf("*/*")) },
                enabled = busy == null, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("从备份恢复")
            }
            busy?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // 导出前再验证一次主密码
    if (askExportPassword) {
        PasswordDialog(
            title = "验证主密码",
            hint = "导出前请再输入一次主密码",
            onDismiss = { askExportPassword = false },
            onConfirm = { input ->
                if (input == masterPassword) {
                    askExportPassword = false
                    val name = "SecureVault_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".svbak"
                    AppLock.beginExternal()
                    exporter.launch(name)
                    null
                } else "主密码不正确"
            }
        )
    }

    if (confirmRestore) {
        PasswordDialog(
            title = "从备份恢复",
            hint = "会用备份覆盖手机上现有的全部账号和文件，无法撤销。请输入备份时的主密码。",
            onDismiss = { confirmRestore = false; restoreUri = null },
            onConfirm = { input ->
                val uri = restoreUri ?: return@PasswordDialog null
                confirmRestore = false
                scope.launch {
                    busy = "正在校验并恢复备份…"
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { BackupManager.restore(context, input, it) }
                                ?: error("无法读取备份文件")
                        }
                    }
                    busy = null
                    restoreUri = null
                    result.fold(
                        { message = "恢复成功。主密码已变为备份时的主密码，请重新解锁；指纹解锁需要重新启用。"; onRestoredPending = true },
                        { message = it.message ?: "恢复失败" }
                    )
                }
                null
            }
        )
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    message = null
                    if (onRestoredPending) { onRestoredPending = false; onRestored() }
                }) { Text("好的") }
            }
        )
    }
}

/** onConfirm 返回错误文字则留在对话框里显示，返回 null 表示通过 */
@Composable
private fun PasswordDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it; error = null },
                    label = { Text("主密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = input.isNotEmpty(), onClick = { error = onConfirm(input) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DriveBackupSection() {
    val context = LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        c as? android.app.Activity
    }
    var enabled by remember { mutableStateOf(com.example.securevault.util.DriveBackup.isEnabled(context)) }
    var refresh by remember { mutableStateOf(0) }
    val lastOk = remember(refresh) { com.example.securevault.util.DriveBackup.lastSuccess(context) }
    val lastErr = remember(refresh) { com.example.securevault.util.DriveBackup.lastError(context) }
    var working by remember { mutableStateOf(false) }

    fun turnOn() {
        val dek = com.example.securevault.util.CryptoUtils.dataKey
        if (dek == null) { android.widget.Toast.makeText(context, "请先解锁", android.widget.Toast.LENGTH_SHORT).show(); return }
        com.example.securevault.util.DriveBackup.enable(context, dek)
        com.example.securevault.util.DriveBackup.clearReauth(context)
        com.example.securevault.util.DriveBackup.backupNow(context)
        enabled = true; refresh++
        android.widget.Toast.makeText(context, "已开启，正在上传第一份备份", android.widget.Toast.LENGTH_SHORT).show()
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        working = false
        val ok = runCatching {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(res.data).accessToken != null
        }.getOrDefault(false)
        if (ok) turnOn() else android.widget.Toast.makeText(context, "没有完成 Google 授权", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun authorize() {
        val act = activity ?: return
        working = true
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(act)
            .authorize(com.example.securevault.util.DriveBackup.authRequest())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    AppLock.beginExternal()
                    consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                } else { working = false; turnOn() }
            }
            .addOnFailureListener { e ->
                working = false
                android.widget.Toast.makeText(context, "连接 Google 失败：${e.message}（需要能访问 Google 的网络和 Google Play 服务）", android.widget.Toast.LENGTH_LONG).show()
            }
    }

    Text("Google Drive 自动备份", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("每天连着 Wi-Fi 时自动上传")
            Text("上传的是加密备份，恢复时需要主密码", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, enabled = !working, onCheckedChange = { on ->
            if (on) authorize() else { com.example.securevault.util.DriveBackup.disable(context); enabled = false }
        })
    }
    if (enabled) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        Text(if (lastOk > 0) "上次成功：${fmt.format(Date(lastOk))}" else "还没有成功上传过", style = MaterialTheme.typography.bodySmall)
        lastErr?.let { Text("上次失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val restricted = remember(refresh) { !pm.isIgnoringBatteryOptimizations(context.packageName) }
        if (restricted) {
            Text("小米手机省电策略可能会拦住每天的自动备份。请在应用详情里打开「自启动」，并把「省电策略」改成「无限制」。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                AppLock.beginExternal()
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)))
            }) { Text("去设置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                com.example.securevault.util.DriveBackup.backupNow(context)
                android.widget.Toast.makeText(context, "已开始上传，稍后回来看结果", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("立即备份") }
            if (com.example.securevault.util.DriveBackup.needsReauth(context)) {
                Button(onClick = { authorize() }) { Text("重新登录 Google") }
            }
            TextButton(onClick = { refresh++ }) { Text("刷新状态") }
        }
    }
}