package top.southfish.engrave.ui.books

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.ui.components.AnimatedTimeText
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.vocabViewModel
import java.io.File

/**
 * 内置轻量文件选择器：点 JSON 文件导入。
 * 有存储权限时从存储根目录逐级浏览任意文件夹；没权限时浏览应用专属目录
 * Android/data/<包名>/files/books（免授权），页内可跳转系统设置授权。
 *
 * 文件条目采用与搜单词结果一致的信息列表卡片（文件名 + 大小/日期/项数），
 * 一屏能看更多文件。[mode] 决定导入行为：
 * - [PickerMode.BOOK]：点词书 JSON 直接导入；
 * - [PickerMode.BACKUP]：点备份 JSON 弹确认后全量恢复（破坏性）。
 */
@Composable
fun FilePickerScreen(
    onImported: () -> Unit = {},
    mode: PickerMode = PickerMode.BOOK,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<FilePickerViewModel> { container ->
        FilePickerViewModel(
            backupManager = container.backupManager,
            appContext = container.appContext,
            mode = mode
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    val timeVisible = rememberTimeTextVisible(listState)

    val lifecycleOwner = LocalLifecycleOwner.current
    // 从系统设置授权回来时（ON_RESUME）重新检查权限并刷新目录
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // targetSdk 29 传统存储：直接弹 READ/WRITE 运行时授权框；
    // 用户也可以去 设置→安全与隐私→许可管理器→文件 手动允许
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermission() }
    fun requestStoragePermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    // 导入成功后停留片刻让用户看到结果，再自动返回来源页
    LaunchedEffect(state.imported) {
        if (state.imported) {
            kotlinx.coroutines.delay(1500)
            onImported()
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = { AnimatedTimeText(timeVisible) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item {
                ListHeader {
                    Text(
                        text = state.dir?.name ?: if (mode == PickerMode.BOOK) "选择词书文件" else "选择备份文件",
                        style = MaterialTheme.typography.caption2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!state.hasPermission) {
                // 无存储权限：当前浏览免授权的应用文件夹 books/，
                // 想浏览整个手表存储就点这个弹系统授权框
                item {
                    Text(
                        "在应用文件夹 books 里选 json\n授权后可从存储根目录浏览任意文件夹",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, start = 24.dp, end = 24.dp)
                    )
                }
                item {
                    Chip(
                        label = { Text("授权浏览手表存储") },
                        onClick = { requestStoragePermission() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            // 路径导航：显示相对根目录的层级路径 + 跳根目录/上一级
            val dir = state.dir
            val root = state.rootDir
            if (dir != null && root != null) {
                val rel = dir.absolutePath
                    .removePrefix(root.absolutePath)
                    .trimStart(File.separatorChar)
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (rel.isEmpty()) "📁 内部存储根目录" else "📁 $rel",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        )
                    }
                }
                if (rel.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { viewModel.openDir(root) },
                                modifier = Modifier.weight(1f)
                            ) { Text("🏠 根目录") }
                            Button(
                                onClick = { dir.parentFile?.let { viewModel.openDir(it) } },
                                modifier = Modifier.weight(1f)
                            ) { Text("⬆ 上一级") }
                        }
                    }
                }
            }

            if (state.entries.isEmpty()) {
                item {
                    Text(
                        when {
                            state.denied -> "该目录无法访问\n系统受限目录不给应用读取"
                            mode == PickerMode.BACKUP -> {
                                if (state.hasPermission) "这里没有备份文件\n把 vocab-backup 的 json 放进任意目录"
                                else "这里没有备份文件\n把备份 json 推进 books 目录"
                            }
                            state.hasPermission -> "这里没有文件\n词书 JSON 放进存储里任意目录都行"
                            else -> "这里没有 json 文件\n把词书 JSON 放进 books 目录"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 文件条目：与搜单词结果同款的信息列表卡片（主行 + 副信息行）
            items(state.entries, key = { it.file.absolutePath }) { entry ->
                val supported = entry.isDir || entry.file.extension.equals("json", true)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colors.surface)
                        .clickable(enabled = !state.busy) { viewModel.onEntryTap(entry) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = (if (entry.isDir) "📁 " else "📄 ") + entry.file.name,
                        style = MaterialTheme.typography.body1,
                        color = if (supported) MaterialTheme.colors.onSurface
                        else MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.detail,
                        style = MaterialTheme.typography.caption2,
                        color = if (supported) MaterialTheme.colors.onSurfaceVariant
                        else MaterialTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            state.message?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.caption2,
                        color = if (state.imported) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    // 备份模式的破坏性恢复确认层
    state.pendingConfirmFile?.let { file ->
        ConfirmBackupOverlay(
            fileName = file.name,
            busy = state.busy,
            onConfirm = { viewModel.confirmBackupImport() },
            onDismiss = { viewModel.cancelBackupImport() }
        )
    }
}

/** 全屏确认层：说明文字 + 确认 / 取消（备份恢复会清空现有数据）。 */
@Composable
private fun ConfirmBackupOverlay(
    fileName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "导入会删除现在所有的数据\n并替换为 $fileName，确认？",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "恢复中…" else "确认恢复") }
            Button(
                onClick = onDismiss,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface),
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消") }
        }
    }
}
