package top.southfish.engrave.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ChipDefaults
import top.southfish.engrave.ui.components.ListHeader
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text

import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel

/**
 * 词书管理：默认词书（随包内置）和本地词书（导入的）。
 *
 * 点词书卡片 = 勾选 / 取消勾选启用（支持多选，可背任意几本书的混合词库），
 * 「使用全部词库」回到全选；默认词书只能导出，本地词书可删可导。
 * 导出直接落到 Download/vocab/（手表上没有文件选择器），导入列出
 * 本应用导出的文件点选恢复，「从设备导入词书」常驻在设备文件里选 JSON。
 */
@Composable
fun BooksScreen(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<BooksViewModel> { container ->
        BooksViewModel(
            wordRepository = container.wordRepository,
            settingsRepository = container.settingsRepository,
            backupManager = container.backupManager
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    rememberRotaryScrollable(listState)

    // 待确认删除的词书：点「删除」先弹确认层，防止误删本地词书
    var pendingDelete by remember { mutableStateOf<top.southfish.engrave.domain.model.WordBook?>(null) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
                    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item { ListHeader { Text("词书管理") } }

            // 全部词书：启用集合为空，所有词混在一起背
            item {
                val allActive = state.activeBookIds.isEmpty()
                Chip(
                    label = { Text(if (allActive) "✓ 使用全部词库" else "使用全部词库") },
                    onClick = { viewModel.selectAllBooks() },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (allActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            items(state.books, key = { it.id }) { book ->
                val sizeBytes = state.sizes[book.id] ?: 0L
                // 「全部词库」态下每本书都参与背诵，但不在单本上做勾选标记；
                // 显式多选时，选中的书高亮 + ✓
                val isChecked = book.id in state.activeBookIds
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Chip(
                        label = { Text(if (isChecked) "✓ ${book.name}" else book.name) },
                        secondaryLabel = {
                            Text(
                                "${book.wordCount} 词 · ${formatSize(sizeBytes)}" +
                                    if (viewModel.isBuiltin(book)) " · 默认" else " · 本地"
                            )
                        },
                        onClick = { viewModel.toggleBook(book.id) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (isChecked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.exportBook(book.id) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("导出") }
                        if (!viewModel.isBuiltin(book)) {
                            Button(
                                onClick = { pendingDelete = book },
                                enabled = !state.busy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("删除") }
                        }
                    }
                }
            }

            item {
                // 常驻入口：从设备存储（有权限可浏览任意文件夹）选 JSON 词书导入。
                // 权限引导放在文件选择器里：没有存储权限时它浏览应用专属
                // 目录（免授权），并提供去系统设置授权的按钮
                Button(
                    onClick = onPickFile,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("从设备导入词书") }
            }

            item { ListHeader { Text("或从本应用的导出恢复") } }
            if (state.backups.isEmpty()) {
                item {
                    Text(
                        "还没有可导入的文件\n先在上方点「导出」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(state.backups.filter { it.displayName.startsWith("book-") }, key = { it.uri.toString() }) { file ->
                Button(
                    onClick = { viewModel.importBook(file.uri) },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) { Text(file.displayName) }
            }

            state.message?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // 删除二次确认层：词书名 + 不可恢复警告（词书、单词和学习记录一起没了）
    pendingDelete?.let { book ->
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
                    text = "删除词书「${book.name}」？\n该词书的全部单词与学习记录将一并删除\n此操作不可恢复",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteBook(book.id)
                    },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("确认删除") }
                Button(
                    onClick = { pendingDelete = null },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / 1024 / 1024} MB" else "${(bytes + 1023) / 1024} KB"
