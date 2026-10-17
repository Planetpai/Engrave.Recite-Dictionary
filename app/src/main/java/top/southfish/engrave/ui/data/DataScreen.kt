package top.southfish.engrave.ui.data

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.ui.components.AnimatedTimeText
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.vocabViewModel

/** 需要用户确认的破坏性操作。 */
private sealed interface ConfirmAction {
    data class Import(val uri: Uri, val name: String) : ConfirmAction
    data object ResetAll : ConfirmAction
}

/**
 * 数据管理：导出 / 导入 / 重置。
 *
 * 导入 = 破坏性恢复（先清空现有全部数据），恢复和重置前都要弹确认。
 * 导出文件落在 Download/vocab/（手表上没有文件选择器），「从导出恢复」
 * 直接列出本应用导出的文件点选恢复；「从设备导入」常驻入口打开文件浏览器，
 * 可选择设备上任意位置（Download、应用专属 books 目录）的备份 JSON。
 */
@Composable
fun DataScreen(
    onPickFile: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<DataViewModel> { container ->
        DataViewModel(backupManager = container.backupManager)
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    val timeVisible = rememberTimeTextVisible(listState)

    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.consumeMessage()
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
            item { ListHeader { Text("数据管理") } }

            item {
                Text(
                    "导出包含：头像昵称、设置、\n词书本体、学会/没学会的词",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = viewModel::exportAll,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) { Text(if (state.busy) "处理中…" else "导出数据") }
            }

            item { ListHeader { Text("从导出恢复") } }

            if (state.backups.isEmpty()) {
                item {
                    Text(
                        "还没有导出文件",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(
                state.backups.filter { it.displayName.startsWith("vocab-backup") },
                key = { it.uri.toString() }
            ) { file ->
                Button(
                    onClick = { confirm = ConfirmAction.Import(file.uri, file.displayName) },
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                ) { Text(file.displayName) }
            }

            // 常驻入口：浏览设备目录里的备份文件（Download 或应用 books 目录），
            // 不会因为导入过一次而隐藏
            item { ListHeader { Text("或从设备目录导入") } }
            item {
                Button(
                    onClick = onPickFile,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                ) { Text("从设备导入备份") }
            }

            item {
                Button(
                    onClick = { confirm = ConfirmAction.ResetAll },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) { Text("重置数据") }
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

    confirm?.let { action ->
        ConfirmOverlay(
            text = when (action) {
                is ConfirmAction.Import ->
                    "导入会删除现在所有的数据\n并替换为 ${action.name}，确认？"
                ConfirmAction.ResetAll ->
                    "将清空全部数据（词书、学习记录、设置），恢复初始状态。确认？"
            },
            confirmLabel = when (action) {
                is ConfirmAction.Import -> "确认导入"
                ConfirmAction.ResetAll -> "确认重置"
            },
            confirmColor = when (action) {
                is ConfirmAction.Import -> MaterialTheme.colors.primary
                ConfirmAction.ResetAll -> MaterialTheme.colors.error
            },
            onConfirm = {
                when (action) {
                    is ConfirmAction.Import -> viewModel.importBackup(action.uri)
                    ConfirmAction.ResetAll -> viewModel.resetAll()
                }
                confirm = null
            },
            onDismiss = { confirm = null }
        )
    }
}

/** 全屏确认层：说明文字 + 确认 / 取消。 */
@Composable
private fun ConfirmOverlay(
    text: String,
    confirmLabel: String,
    confirmColor: Color,
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
                text = text,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(backgroundColor = confirmColor),
                modifier = Modifier.fillMaxWidth()
            ) { Text(confirmLabel) }
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface),
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消") }
        }
    }
}
