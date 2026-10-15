package com.example.securevault.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.securevault.data.VaultItem
import com.example.securevault.data.VaultKind
import com.example.securevault.data.VaultStore
import com.example.securevault.util.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DOC_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/plain",
    "image/*",
    "*/*"
)

@Composable
fun VaultFilesScreen(store: VaultStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var filter by remember { mutableStateOf<VaultKind?>(null) }
    var viewing by remember { mutableStateOf<VaultItem?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var tagBatch by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuItem by remember { mutableStateOf<VaultItem?>(null) }      // 长按弹出菜单
    var renameItem by remember { mutableStateOf<VaultItem?>(null) }
    var tagItem by remember { mutableStateOf<VaultItem?>(null) }
    var deleteItem by remember { mutableStateOf<VaultItem?>(null) }   // 刚导入、等待加标签的文件 id

    fun reload() { scope.launch { items = withContext(Dispatchers.IO) { store.list() } } }
    LaunchedEffect(Unit) { reload() }

    fun importAll(uris: List<Uri>, kind: VaultKind) {
        if (uris.isEmpty()) return
        scope.launch {
            busyText = "正在加密导入 ${uris.size} 个文件…"
            var failed = 0
            val imported = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> runCatching { store.import(uri, kind) }.onSuccess { imported += it.id }.onFailure { failed++ } }
            }
            if (imported.isNotEmpty()) tagBatch = imported
            busyText = null
            items = withContext(Dispatchers.IO) { store.list() }
            val prefs = context.getSharedPreferences("ui_hints", android.content.Context.MODE_PRIVATE)
            message = when {
                failed > 0 -> "有 $failed 个文件导入失败"
                !prefs.getBoolean("import_hint_shown", false) -> {
                    // 这句提示只显示一次
                    prefs.edit().putBoolean("import_hint_shown", true).apply()
                    "已加密保存 ${uris.size} 个文件。相册或文件夹里的原文件不会被改动，不需要的话可以自己去删掉。"
                }
                else -> null
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        importAll(uris, VaultKind.PHOTO)
    }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importAll(uris, VaultKind.DOCUMENT)
    }

    val current = viewing
    if (current != null) {
        VaultViewer(store, current, allTags = items.flatMap { it.tags }.distinct().sorted(),
            onBack = { viewing = null; reload() }, onDeleted = { viewing = null; reload() })
        return
    }

    var menuOpen by remember { mutableStateOf(false) }

    val allTags = items.flatMap { it.tags }.distinct().sorted()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("保险箱") }) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("添加照片") },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = {
                            menuOpen = false
                            AppLock.beginExternal()
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("添加文件（PDF、Word 等）") },
                        leadingIcon = { Icon(Icons.Default.Description, null) },
                        onClick = {
                            menuOpen = false
                            AppLock.beginExternal()
                            docPicker.launch(DOC_MIME_TYPES)
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("全部") })
                FilterChip(selected = filter == VaultKind.PHOTO, onClick = { filter = VaultKind.PHOTO }, label = { Text("照片") })
                FilterChip(selected = filter == VaultKind.DOCUMENT, onClick = { filter = VaultKind.DOCUMENT }, label = { Text("文件") })
            }
            busyText?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (allTags.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = if (selectedTag == tag) null else tag },
                            label = { Text("#$tag") }
                        )
                    }
                }
            }
            val shown = items.filter { (filter == null || it.kind == filter) && (selectedTag == null || selectedTag in it.tags) }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有内容，点右下角 + 添加照片或文件")
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { item ->
                        VaultRow(store, item, onClick = { viewing = item }, onLongClick = { menuItem = item })
                    }
                }
            }
        }
    }

    if (tagBatch.isNotEmpty()) {
        TagEditorDialog(
            title = "给刚导入的 ${tagBatch.size} 个文件加标签",
            allTags = allTags,
            initial = emptyList(),
            onDismiss = { tagBatch = emptyList() },
            onConfirm = { tags ->
                val ids = tagBatch
                tagBatch = emptyList()
                if (tags.isNotEmpty()) scope.launch {
                    withContext(Dispatchers.IO) { store.setTags(ids, tags) }
                    reload()
                }
            }
        )
    }

    menuItem?.let { item ->
        AlertDialog(
            onDismissRequest = { menuItem = null },
            title = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { menuItem = null; renameItem = item }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("重命名", Modifier.weight(1f))
                    }
                    TextButton(onClick = { menuItem = null; tagItem = item }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Label, null); Spacer(Modifier.width(8.dp)); Text("改标签", Modifier.weight(1f))
                    }
                    TextButton(onClick = { menuItem = null; deleteItem = item }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("删除", Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuItem = null }) { Text("取消") } }
        )
    }

    renameItem?.let { item ->
        val dot = item.name.lastIndexOf('.')
        val ext = if (dot > 0) item.name.substring(dot) else ""
        var input by remember(item.id) { mutableStateOf(if (dot > 0) item.name.substring(0, dot) else item.name) }
        AlertDialog(
            onDismissRequest = { renameItem = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    suffix = { if (ext.isNotEmpty()) Text(ext) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = input.isNotBlank(), onClick = {
                    val newName = input.trim() + ext
                    renameItem = null
                    scope.launch { withContext(Dispatchers.IO) { store.rename(item.id, newName) }; reload() }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameItem = null }) { Text("取消") } }
        )
    }

    tagItem?.let { item ->
        TagEditorDialog(
            title = "编辑标签",
            allTags = allTags,
            initial = item.tags,
            onDismiss = { tagItem = null },
            onConfirm = { tags ->
                tagItem = null
                scope.launch { withContext(Dispatchers.IO) { store.updateTags(item.id, tags) }; reload() }
            }
        )
    }

    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("删除文件？") },
            text = { Text("「${item.name}」会从保险箱里永久删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteItem = null
                    scope.launch { withContext(Dispatchers.IO) { store.delete(item) }; reload() }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("取消") } }
        )
    }

    message?.let { msg ->        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好的") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultRow(store: VaultStore, item: VaultItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    if (item.isImage) {
        LaunchedEffect(item.id) {
            thumb = withContext(Dispatchers.IO) { runCatching { decodeSampled(store.readBytes(item), 160) }.getOrNull() }
        }
    }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val t = thumb
                if (t != null) {
                    Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        when {
                            item.isImage -> Icons.Default.Image
                            item.isPdf -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.Description
                        },
                        contentDescription = null
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val meta = Formatter.formatShortFileSize(context, item.size) +
                    if (item.tags.isEmpty()) "" else "  ·  " + item.tags.joinToString(" ") { "#$it" }
                Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.Lock, contentDescription = "已加密", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VaultViewer(store: VaultStore, item: VaultItem, allTags: List<String>, onBack: () -> Unit, onDeleted: () -> Unit) {
    var tags by remember(item.id) { mutableStateOf(item.tags) }
    var editTags by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) {
            try {
                when {
                    item.isImage -> image = decodeSampled(store.readBytes(item), 2048)
                    item.isPdf -> {
                        val tmp = store.decryptToTemp(item)
                        try {
                            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { renderer ->
                                    val pages = mutableListOf<Bitmap>()
                                    for (i in 0 until minOf(renderer.pageCount, 50)) {
                                        renderer.openPage(i).use { page ->
                                            val w = 1080
                                            val h = (w.toFloat() * page.height / page.width).toInt()
                                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                            bmp.eraseColor(AColor.WHITE)
                                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            pages += bmp
                                        }
                                    }
                                    pdfPages = pages
                                }
                            }
                        } finally {
                            tmp.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                error = "无法打开：${e.message}"
            }
        }
        loading = false
    }
    DisposableEffect(Unit) { onDispose { VaultStore.clearTemp(context) } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(item.mime)) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { store.exportTo(item, it) } }.isSuccess
            }
            toast = if (ok) "已导出（导出的文件是未加密的，注意保管）" else "导出失败"
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { AppLock.beginExternal(); exporter.launch(item.name) }) {
                        Icon(Icons.Default.Download, "导出")
                    }
                    IconButton(onClick = { editTags = true }) { Icon(Icons.Default.Label, "标签") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                error != null -> Text(error!!)
                image != null -> Image(image!!.asImageBitmap(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                pdfPages.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pdfPages) { p ->
                        Image(p.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.Description, null, Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("这种文件暂不支持在 App 内预览，已加密保存。需要查看时可以点右上角导出。")
                }
            }
        }
    }

    if (editTags) {
        TagEditorDialog(
            title = "编辑标签",
            allTags = (allTags + tags).distinct().sorted(),
            initial = tags,
            onDismiss = { editTags = false },
            onConfirm = { newTags ->
                editTags = false
                tags = newTags
                scope.launch { withContext(Dispatchers.IO) { store.updateTags(item.id, newTags) } }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个文件？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(item) }
                        onDeleted()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    toast?.let {
        AlertDialog(onDismissRequest = { toast = null }, text = { Text(it) },
            confirmButton = { TextButton(onClick = { toast = null }) { Text("好的") } })
    }
}

/** 选已有标签或新建标签，照片和文件共用 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    title: String,
    allTags: List<String>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(initial.toSet()) }
    var options by remember { mutableStateOf((allTags + initial).distinct()) }
    var newTag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (options.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { tag ->
                            FilterChip(
                                selected = tag in selected,
                                onClick = { selected = if (tag in selected) selected - tag else selected + tag },
                                label = { Text(tag) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("新标签，如：银行卡、证件") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(enabled = newTag.isNotBlank(), onClick = {
                            val t = newTag.trim()
                            options = (options + t).distinct()
                            selected = selected + t
                            newTag = ""
                        }) { Icon(Icons.Default.Add, "添加标签") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val extra = newTag.trim().takeIf { it.isNotEmpty() }
                onConfirm((selected + listOfNotNull(extra)).toList())
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("跳过") } }
    )
}

private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}