package com.example.securevault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.securevault.data.Account
import com.example.securevault.util.CryptoUtils
import com.example.securevault.util.PasswordTools
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    existing: Account? = null,
    masterPassword: String,
    allTags: List<String>,
    onSave: (Account) -> Unit,
    onBack: () -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var email by remember { mutableStateOf(existing?.emailOrUsername ?: "") }
    var password by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var selectedTags by remember { mutableStateOf(existing?.tags?.toSet() ?: emptySet()) }
    var newTag by remember { mutableStateOf("") }
    var showGenerator by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加账号" else "编辑账号") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("名称（如 Gmail / 守望先锋）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("邮箱或用户名") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(if (existing == null) "密码" else "密码（留空则不修改）") },
                trailingIcon = {
                    IconButton(onClick = { showGenerator = true }) { Icon(Icons.Default.AutoAwesome, contentDescription = "生成密码") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            StrengthBar(password)
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth(), minLines = 3)

            Text("标签（可多选）", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = newTag, onValueChange = { newTag = it }, label = { Text("新标签") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (newTag.isNotBlank()) {
                        selectedTags = selectedTags + newTag.trim()
                        newTag = ""
                    }
                }) { Text("添加") }
            }

            // 已有标签选择
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (allTags + selectedTags).distinct().forEach { tag ->
                    FilterChip(
                        selected = selectedTags.contains(tag),
                        onClick = {
                            selectedTags = if (selectedTags.contains(tag)) selectedTags - tag else selectedTags + tag
                        },
                        label = { Text(tag) }
                    )
                }
            }

            if (showGenerator) {
                GeneratorDialog(onDismiss = { showGenerator = false }, onUse = { password = it; showGenerator = false })
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val encrypted = if (password.isNotBlank()) {
                        CryptoUtils.encrypt(password, masterPassword)
                    } else existing?.encryptedPassword ?: ""

                    val account = Account(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        title = title,
                        emailOrUsername = email,
                        encryptedPassword = encrypted,
                        notes = notes,
                        tags = selectedTags.toList()
                    )
                    onSave(account)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && email.isNotBlank()
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun StrengthBar(pwd: String) {
    val s = PasswordTools.strength(pwd)
    if (s == PasswordTools.Strength.EMPTY) return
    val color = when (s.level) { 1 -> Color(0xFFE53935); 2 -> Color(0xFFFFA000); 3 -> Color(0xFF7CB342); else -> Color(0xFF2E7D32) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { s.level / 4f }, color = color, modifier = Modifier.weight(1f))
        Text("强度：${s.label}", color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var length by remember { mutableFloatStateOf(16f) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var generated by remember { mutableStateOf("") }
    fun regen() { generated = PasswordTools.generate(length.toInt(), upper, digits, symbols) }
    LaunchedEffect(length.toInt(), upper, digits, symbols) { regen() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(generated, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                StrengthBar(generated)
                Text("长度：${length.toInt()}")
                Slider(value = length, onValueChange = { length = it }, valueRange = 8f..32f, steps = 23)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(upper, { upper = it }); Text("大写字母") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(digits, { digits = it }); Text("数字") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(symbols, { symbols = it }); Text("符号") }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(generated) }) { Text("使用") } },
        dismissButton = {
            Row {
                TextButton(onClick = { regen() }) { Text("换一个") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}