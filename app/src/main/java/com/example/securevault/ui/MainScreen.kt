package com.example.securevault.ui

import com.example.securevault.util.UserVerifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.os.SystemClock
import android.widget.Toast
import com.example.securevault.util.ClipboardHelper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.securevault.data.Account
import com.example.securevault.data.AccountRepository
import com.example.securevault.ui.screens.AccountEditScreen
import com.example.securevault.ui.screens.AccountListScreen
import com.example.securevault.ui.screens.LockScreen
import com.example.securevault.util.CryptoUtils
import com.example.securevault.util.HibpClient
import com.example.securevault.util.KeystoreBiometricHelper
import com.example.securevault.util.PwnedResult
import com.example.securevault.util.SecurityManager
import com.example.securevault.util.AppLock
import com.example.securevault.data.VaultStore
import com.example.securevault.ui.screens.VaultFilesScreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import com.example.securevault.ui.screens.SettingsScreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 旧版本（107）把密码写死成 demo123 加密，解锁后迁移到用户自己的主密码
private const val LEGACY_PASSWORD = "demo123"

// 切到后台超过这个时间再回来就自动上锁
private const val AUTO_LOCK_MS = 30_000L

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()

    var masterPassword by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockError by remember { mutableStateOf<String?>(null) }
    var isSetup by remember { mutableStateOf(true) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var offerBiometric by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    fun refreshState() {
        scope.launch {
            val (setup, bio) = withContext(Dispatchers.Default) {
                !SecurityManager.hasMasterPassword(context) to
                    (SecurityManager.isBiometricEnabled(context) && KeystoreBiometricHelper.isBiometricAvailable(context))
            }
            isSetup = setup
            biometricEnabled = bio
            ready = true
        }
    }

    fun startBiometric() {
        KeystoreBiometricHelper.unlock(
            activity,
            onPassword = { pwd ->
                lockError = null
                masterPassword = pwd
            },
            onError = { msg ->
                lockError = msg
                if (msg != null) refreshState()
            }
        )
    }

    fun afterUnlock(pwd: String) {
        masterPassword = pwd
        lockError = null
        if (!SecurityManager.isBiometricEnabled(context) &&
            !SecurityManager.isBiometricDeclined(context) &&
            KeystoreBiometricHelper.isBiometricAvailable(context)
        ) offerBiometric = true
    }

    LaunchedEffect(Unit) { refreshState() }

    // 自动上锁：进入后台记时间，回到前台超时就清掉内存里的主密码
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var backgroundAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (AppLock.consumeExternal()) { backgroundAt = 0L; return@LifecycleEventObserver }
                    if (masterPassword != null && backgroundAt > 0 &&
                        SystemClock.elapsedRealtime() - backgroundAt > AUTO_LOCK_MS
                    ) {
                        masterPassword = null
                        offerBiometric = false
                        ClipboardHelper.clearNow(context)
                        UserVerifier.reset()
                        CryptoUtils.clearKeyCache()
                        refreshState()
                    }
                    backgroundAt = 0L
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pwd = masterPassword
    if (pwd == null) {
        if (!ready) return
        // 锁屏出现时，已启用指纹就自动弹出指纹验证
        LaunchedEffect(biometricEnabled) { if (biometricEnabled) startBiometric() }
        LockScreen(
            isSetup = isSetup,
            isBusy = busy,
            externalError = lockError,
            biometricEnabled = biometricEnabled,
            onUnlock = { input ->
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.Default) { SecurityManager.verifyMasterPassword(context, input) }
                    busy = false
                    if (ok) afterUnlock(input) else lockError = "主密码不正确"
                }
            },
            onSetup = { input ->
                scope.launch {
                    busy = true
                    withContext(Dispatchers.Default) { SecurityManager.setMasterPassword(context, input) }
                    busy = false
                    isSetup = false
                    afterUnlock(input)
                }
            },
            onBiometric = { startBiometric() }
        )
    } else {
        HomeScreen(pwd, onRestored = {
            CryptoUtils.clearKeyCache()
            masterPassword = null
            offerBiometric = false
            biometricEnabled = false
            refreshState()
        })
        if (offerBiometric) {
            AlertDialog(
                onDismissRequest = { offerBiometric = false },
                title = { Text("启用指纹解锁？") },
                text = { Text("以后打开应用时，可以直接用指纹解锁，不用每次输入主密码。") },
                confirmButton = {
                    TextButton(onClick = {
                        offerBiometric = false
                        KeystoreBiometricHelper.enable(activity, pwd) { ok, _ ->
                            if (ok) biometricEnabled = true
                        }
                    }) { Text("启用") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        offerBiometric = false
                        SecurityManager.setBiometricDeclined(context)
                    }) { Text("暂不") }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(masterPassword: String, onRestored: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var store by remember { mutableStateOf<VaultStore?>(null) }
    LaunchedEffect(masterPassword) {
        store = withContext(Dispatchers.Default) { VaultStore.open(context, masterPassword) }
    }
    DisposableEffect(Unit) { onDispose { VaultStore.clearTemp(context) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Key, null) }, label = { Text("账号") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Folder, null) }, label = { Text("保险箱") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Column(Modifier.padding(padding).fillMaxSize()) {
          val driveErr = remember(tab) {
              if (com.example.securevault.util.DriveBackup.isEnabled(context)) com.example.securevault.util.DriveBackup.lastError(context) else null
          }
          if (driveErr != null && tab != 2) {
              androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                  modifier = Modifier.fillMaxWidth().clickable { tab = 2 }) {
                  Text("自动备份失败：$driveErr（点这里处理）", modifier = Modifier.padding(12.dp),
                      color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer)
              }
          }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (store == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (tab == 0) AccountsTab(masterPassword)
            else if (tab == 2) SettingsScreen(masterPassword, onRestored)
            else {
                val s = store
                if (s == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else VaultFilesScreen(s)
            }
        }
        }
    }
}

@Composable
private fun AccountsTab(masterPassword: String) {
    val context = LocalContext.current
    val repository = remember { AccountRepository(context).also { it.setMasterPassword(masterPassword) } }
    var accounts by remember { mutableStateOf(repository.getAll()) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var hibpMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }   // 等待主密码验证的操作
    var revealed by remember { mutableStateOf<Pair<String, String?>?>(null) } // 标题 to 明文（null=解密中）

    // 查看/复制前先验证：指纹优先，不行就输主密码
    fun withVerify(reason: String, action: () -> Unit) {
        if (!UserVerifier.isEnabled(context)) { action(); return }   // 默认关闭
        val activity = context.findFragmentActivity()
        if (activity == null) { pendingAction = action; return }
        UserVerifier.verify(activity, reason, onOk = action, onFallback = { pendingAction = action })
    }

    fun decryptThen(account: Account, block: (String) -> Unit) {
        scope.launch {
            val plain = withContext(Dispatchers.Default) {
                runCatching { CryptoUtils.decrypt(account.encryptedPassword, masterPassword) }.getOrNull()
            }
            if (plain == null) Toast.makeText(context, "这条密码无法解密，请重新编辑并保存一次", Toast.LENGTH_SHORT).show()
            else block(plain)
        }
    }

    // 一次性迁移：能用 demo123 解开的旧密码，改用当前主密码重新加密（放在后台线程，不卡界面）
    LaunchedEffect(masterPassword) {
        if (masterPassword == LEGACY_PASSWORD) return@LaunchedEffect
        val migrated = withContext(Dispatchers.Default) {
            var changed = false
            val list = repository.getAll().map { acc ->
                if (CryptoUtils.isV2(acc.encryptedPassword)) return@map acc
                try {
                    val plain = CryptoUtils.decrypt(acc.encryptedPassword, LEGACY_PASSWORD)
                    changed = true
                    acc.copy(encryptedPassword = CryptoUtils.encrypt(plain, masterPassword))
                } catch (e: Exception) {
                    acc
                }
            }
            if (changed) { repository.saveAll(list); list } else null
        }
        if (migrated != null) accounts = repository.getAll()
    }

    // 一次性迁移：旧格式（每条单独 PBKDF2）改成用数据密钥加密，之后查看/复制都是瞬间解密
    LaunchedEffect(Unit) {
        val changed = withContext(Dispatchers.Default) {
            val all = repository.getAll()
            if (all.all { CryptoUtils.isV2(it.encryptedPassword) || it.encryptedPassword.isEmpty() }) return@withContext false
            val list = all.map { acc ->
                if (acc.encryptedPassword.isEmpty() || CryptoUtils.isV2(acc.encryptedPassword)) acc
                else runCatching { acc.copy(encryptedPassword = CryptoUtils.encrypt(CryptoUtils.decrypt(acc.encryptedPassword, masterPassword), masterPassword)) }
                    .getOrDefault(acc)
            }
            repository.saveAll(list); true
        }
        if (changed) accounts = repository.getAll()
    }

    val allTags = accounts.flatMap { it.tags }.distinct().sorted()

    if (showEdit) {
        // 系统返回键：关掉编辑页回到列表，而不是退出应用
        BackHandler { showEdit = false; editingAccount = null }
        AccountEditScreen(
            existing = editingAccount,
            masterPassword = masterPassword,
            allTags = allTags,
            onSave = { account ->
                if (editingAccount == null) repository.add(account) else repository.update(account)
                accounts = repository.getAll()
                showEdit = false
                editingAccount = null
            },
            onBack = { showEdit = false; editingAccount = null }
        )
    } else {
        AccountListScreen(
            accounts = accounts,
            allTags = allTags,
            selectedTag = selectedTag,
            onTagSelected = { selectedTag = it },
            onAddClick = { editingAccount = null; showEdit = true },
            onEditClick = { editingAccount = it; showEdit = true },
            onDeleteClick = { id ->
                repository.delete(id)
                accounts = repository.getAll()
            },
            onCopyPassword = { account ->
                withVerify("复制「${account.title}」的密码") {
                    decryptThen(account) { plain ->
                        ClipboardHelper.copySensitive(context, plain)
                        Toast.makeText(context, "已复制，30 秒后自动清空", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onViewPassword = { account ->
                withVerify("查看「${account.title}」的密码") {
                    revealed = account.title to null   // 先弹窗
                    decryptThen(account) { plain -> if (revealed?.first == account.title) revealed = account.title to plain }
                }
            },
            onCheckBreach = { account ->
                scope.launch {
                    hibpMessage = "正在检查…"
                    val plain = withContext(Dispatchers.Default) {
                        runCatching { CryptoUtils.decrypt(account.encryptedPassword, masterPassword) }.getOrNull()
                    }
                    hibpMessage = if (plain == null) {
                        "这条密码无法解密，请重新编辑并保存一次"
                    } else when (val r = HibpClient.checkPassword(plain)) {
                        is PwnedResult.Clean -> "「${account.title}」的密码未出现在已知泄露数据中"
                        is PwnedResult.Pwned -> "「${account.title}」的密码在泄露数据中出现过 ${r.count} 次，请尽快修改"
                        is PwnedResult.Error -> "查询失败：${r.message}"
                    }
                }
            }
        )
    }

    revealed?.let { (title, plain) ->
        // 30 秒后自动收起
        LaunchedEffect(title) { kotlinx.coroutines.delay(30_000); revealed = null }
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(title) },
            text = {
                if (plain == null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                else SelectionContainer {
                    Text(plain, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace))
                }
            },
            confirmButton = { TextButton(onClick = { revealed = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(enabled = plain != null, onClick = {
                    ClipboardHelper.copySensitive(context, plain ?: return@TextButton)
                    Toast.makeText(context, "已复制，30 秒后自动清空", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            }
        )
    }

    pendingAction?.let { action ->
        var input by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("验证主密码") },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it; wrong = false },
                    label = { Text("主密码") }, singleLine = true, isError = wrong,
                    supportingText = { if (wrong) Text("主密码不正确") },
                    visualTransformation = PasswordVisualTransformation()
                )
            },
            confirmButton = {
                TextButton(enabled = input.isNotEmpty(), onClick = {
                    if (input == masterPassword) { pendingAction = null; UserVerifier.markVerified(); action() }
                    else wrong = true
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("取消") } }
        )
    }
    hibpMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { hibpMessage = null },
            title = { Text("泄露检查结果") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { hibpMessage = null }) { Text("确定") } }
        )
    }
}

private fun android.content.Context.findFragmentActivity(): androidx.fragment.app.FragmentActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is androidx.fragment.app.FragmentActivity) return c
        c = c.baseContext
    }
    return null
}