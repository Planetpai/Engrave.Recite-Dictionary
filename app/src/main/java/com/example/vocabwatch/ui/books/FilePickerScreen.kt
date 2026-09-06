package com.example.vocabwatch.ui.books

import android.os.Environment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel
import java.io.File

/**
 * 内置轻量文件选择器：从 Download 出发浏览目录、点 JSON 词书导入。
 * 只读目录列表 + json 过滤，不需要任何第三方应用配合。
 */
@Composable
fun FilePickerScreen(
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<FilePickerViewModel> { container ->
        FilePickerViewModel(backupManager = container.backupManager)
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    // 导入成功后停留片刻让用户看到结果，再返回词书管理
    LaunchedEffect(state.imported) {
        if (state.imported) {
            kotlinx.coroutines.delay(1500)
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ListHeader {
                    Text(
                        text = state.dir?.name ?: "选择词书文件",
                        style = MaterialTheme.typography.caption2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            state.dir?.parentFile?.let { parent ->
                item {
                    Chip(
                        label = { Text("⬆ 上一级") },
                        onClick = { viewModel.openDir(parent) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            if (state.entries.isEmpty()) {
                item {
                    Text(
                        "这里没有 json 文件\n把词书 JSON 放进 Download 目录",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            items(state.entries, key = { it.file.absolutePath }) { entry ->
                Chip(
                    label = {
                        Text(
                            (if (entry.isDir) "📁 " else "") + entry.file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    secondaryLabel = if (!entry.isDir) {
                        { Text(formatPickerSize(entry.file.length())) }
                    } else {
                        null
                    },
                    onClick = {
                        if (entry.isDir) viewModel.openDir(entry.file) else viewModel.import(entry.file)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            state.message?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.caption2,
                        color = if (state.imported) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun formatPickerSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${bytes / 1024 / 1024} MB" else "${(bytes + 1023) / 1024} KB"
