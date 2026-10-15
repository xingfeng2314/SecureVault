package com.example.securevault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(
    isSetup: Boolean,
    isBusy: Boolean,
    externalError: String?,
    biometricEnabled: Boolean,
    onUnlock: (String) -> Unit,
    onSetup: (String) -> Unit,
    onBiometric: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val shownError = error ?: externalError

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isSetup) "设置主密码" else "解锁 SecureVault",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("主密码") },
            singleLine = true,
            enabled = !isBusy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (isSetup) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = { Text("确认主密码") },
                singleLine = true,
                enabled = !isBusy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        shownError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !isBusy,
            onClick = {
                if (isSetup) {
                    when {
                        password.length < 6 -> error = "主密码至少 6 位"
                        password != confirm -> error = "两次输入不一致"
                        else -> onSetup(password)
                    }
                } else {
                    if (password.isBlank()) error = "请输入主密码" else onUnlock(password)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(if (isSetup) "设置并进入" else "解锁")
            }
        }

        if (!isSetup && biometricEnabled) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onBiometric, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("指纹解锁")
            }
        }
    }
}