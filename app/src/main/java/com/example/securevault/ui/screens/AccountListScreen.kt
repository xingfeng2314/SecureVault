package com.example.securevault.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.securevault.data.Account

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    accounts: List<Account>,
    allTags: List<String>,
    selectedTag: String?,
    onTagSelected: (String?) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Account) -> Unit,
    onDeleteClick: (String) -> Unit,
    onCheckBreach: (Account) -> Unit,
    onCopyPassword: (Account) -> Unit = {},
    onViewPassword: (Account) -> Unit = {}
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("我的账号", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 标签筛选
            if (allTags.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = 0) {
                    Tab(
                        selected = selectedTag == null,
                        onClick = { onTagSelected(null) },
                        text = { Text("全部") }
                    )
                    allTags.forEach { tag ->
                        Tab(
                            selected = selectedTag == tag,
                            onClick = { onTagSelected(tag) },
                            text = { Text(tag) }
                        )
                    }
                }
            }

            val filtered = if (selectedTag == null) accounts
            else accounts.filter { it.tags.contains(selectedTag) }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无账号，点击右上角 + 添加")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(filtered, key = { it.id }) { account ->
                        AccountCard(
                            account = account,
                            onEdit = { onEditClick(account) },
                            onDelete = { onDeleteClick(account.id) },
                            onCheckBreach = { onCheckBreach(account) },
                            onCopyPassword = { onCopyPassword(account) },
                            onViewPassword = { onViewPassword(account) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AccountCard(
    account: Account,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheckBreach: () -> Unit,
    onCopyPassword: () -> Unit = {},
    onViewPassword: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(account.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onViewPassword) { Icon(Icons.Default.Visibility, contentDescription = "查看密码") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, null) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null) }
            }
            Text(account.emailOrUsername, style = MaterialTheme.typography.bodyMedium)
            if (account.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    account.tags.forEach { tag ->
                        SuggestionChip(onClick = {}, label = { Text(tag) })
                    }
                }
            }
            if (account.notes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(account.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyPassword) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制密码")
                }
                OutlinedButton(onClick = onCheckBreach) { Text("泄露检查") }
            }
        }
    }
}
