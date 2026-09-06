package com.example.vocabwatch.ui.books

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 词书管理：默认词书（随包内置）和本地词书（导入的）。
 *
 * 点词书卡片 = 设为背诵词书；默认词书只能导出，本地词书可删可导。
 * 导出直接落到 Download/vocab/（手表上没有文件选择器），导入列出
 * 本应用导出的文件点选恢复。
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
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("词书管理") } }

            items(state.books, key = { it.id }) { book ->
                val sizeBytes = state.sizes[book.id] ?: 0L
                val isActive = state.activeBookId == book.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Card(
                        onClick = { viewModel.selectBook(book.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = book.name + if (isActive) " ✓" else "",
                            style = MaterialTheme.typography.title3
                        )
                        Text(
                            text = "${book.wordCount} 词 · ${formatSize(sizeBytes)}" +
                                if (viewModel.isBuiltin(book)) " · 默认" else " · 本地",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
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
                                onClick = { viewModel.deleteBook(book.id) },
                                enabled = !state.busy,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = MaterialTheme.colors.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) { Text("删除") }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (Environment.isExternalStorageManager()) {
                            onPickFile()
                        } else {
                            // 逐级降级：包名定向权限页 → 通用权限页 → 应用详情页
                            val granted = listOf(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:" + context.packageName)),
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:" + context.packageName))
                            ).any { intent ->
                                runCatching {
                                    context.startActivity(intent)
                                    true
                                }.getOrDefault(false)
                            }
                            if (!granted) {
                                android.widget.Toast.makeText(
                                    context, "请在系统设置里允许所有文件访问", android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("从文件选择导入") }
            }

            item { ListHeader { Text("或从本应用的导出恢复") } }
            if (state.backups.isEmpty()) {
                item {
                    Text(
                        "还没有可导入的文件\n先在上方点「导出」",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
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
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / 1024 / 1024} MB" else "${(bytes + 1023) / 1024} KB"
