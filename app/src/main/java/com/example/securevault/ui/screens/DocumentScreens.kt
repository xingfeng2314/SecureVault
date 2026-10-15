package com.example.securevault.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.securevault.data.DocumentEntity
import com.example.securevault.data.DocumentType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    documents: List<DocumentEntity>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("证件 / 卡片") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        if (documents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂无证件，点击右下角添加")
            }
        } else {
            LazyColumn(Modifier.padding(padding).padding(16.dp)) {
                items(documents, key = { it.id }) { doc ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(Icons.Default.CreditCard, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(doc.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onDelete(doc.id) }) { Text("删除") }
                            }
                            Text(DocumentType.values().find { it.name == doc.type }?.label ?: doc.type)
                            if (doc.number.isNotBlank()) Text("号码：${doc.number.take(4)}****")
                            if (doc.expiry.isNotBlank()) Text("有效期：${doc.expiry}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentEditScreen(
    onSave: (DocumentEntity, List<Uri>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(DocumentType.BANK_CARD) }
    var title by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cvv by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedImages = selectedImages + uris
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Text("添加证件 / 卡片", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        // 类型选择
        DocumentType.values().forEach { t ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = type == t, onClick = { type = t })
                Text(t.label)
            }
        }

        OutlinedTextField(title, { title = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(number, { number = it }, label = { Text("卡号 / 证件号") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(expiry, { expiry = it }, label = { Text("有效期") }, modifier = Modifier.fillMaxWidth())
        if (type == DocumentType.BANK_CARD) {
            OutlinedTextField(cvv, { cvv = it }, label = { Text("CVV") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Button(onClick = { picker.launch("image/*") }) { Text("添加照片 (${selectedImages.size})") }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                val entity = DocumentEntity(
                    type = type.name,
                    title = title,
                    number = number,
                    expiry = expiry,
                    cvv = cvv,
                    notes = notes
                )
                onSave(entity, selectedImages)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = title.isNotBlank()
        ) { Text("保存") }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
}
