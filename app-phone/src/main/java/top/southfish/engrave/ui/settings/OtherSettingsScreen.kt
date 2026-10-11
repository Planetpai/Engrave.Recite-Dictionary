package top.southfish.engrave.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.ui.components.Chip
import top.southfish.engrave.ui.components.ListHeader
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.timeTextPadding
import top.southfish.engrave.ui.vocabViewModel

/**
 * 「其他」设置：从背单词设置里拆出的低频项——详情页释义显示、单词发音。
 *
 * 单词发音仅控制学习卡片展示英文词头时是否自动朗读，与详情页无关
 * （详情页点词头朗读始终可用）。系统缺英文 TTS 语音数据时禁止开启，
 * 并在下方给出灰色提示。
 */
@Composable
fun OtherSettingsScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StudySettingsViewModel> { container ->
        StudySettingsViewModel(
            settingsRepository = container.settingsRepository,
            appContext = container.appContext,
            ttsSpeaker = container.ttsSpeaker
        )
    }
    val state by viewModel.uiState.collectAsState()

    // 加载门禁：DataStore 首读完成前只显示占位，与背单词设置页同款
    if (!state.loaded) {
        Text(
            "读取设置…",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxSize().padding(top = 24.dp)
        )
        return
    }

    OtherSettingsContent(state = state, viewModel = viewModel, modifier = modifier)
}

@Composable
private fun OtherSettingsContent(
    state: StudySettingsUiState,
    viewModel: StudySettingsViewModel,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    rememberRotaryScrollable(listState)

    Scaffold(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = timeTextPadding(),
                bottom = 16.dp
            )
        ) {
            item { ListHeader { Text("其他设置") } }

            item {
                Text(
                    "详情页释义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
            item {
                Chip(
                    label = {
                        Text(
                            when (state.definitionDisplay) {
                                DefinitionDisplay.ZH -> "只看中文释义"
                                DefinitionDisplay.EN -> "只看英文解释"
                                DefinitionDisplay.BOTH -> "中英都要"
                            }
                        )
                    },
                    onClick = viewModel::cycleDefinitionDisplay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            item {
                Text(
                    "单词发音",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
            item {
                Chip(
                    label = {
                        Text(if (state.ttsEnabled) "已开启 · 卡片自动发音" else "已关闭")
                    },
                    onClick = viewModel::toggleTts,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            // TTS 检测不可用：开关拒绝开启，灰字说明原因（历史已开启的
            // 配置保持原状态，不静默改写）。单独成 item 才能排在按钮正下方
            if (state.ttsAvailable == false) {
                item {
                    Text(
                        "暂无tts语音可用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
