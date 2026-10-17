package top.southfish.engrave.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import top.southfish.engrave.ui.components.AnimatedTimeText
import kotlinx.coroutines.launch
import top.southfish.engrave.BuildConfig
import top.southfish.engrave.AppReleaseInfo
import top.southfish.engrave.domain.model.DefinitionDisplay
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.components.rememberTimeTextVisible
import top.southfish.engrave.ui.components.timeTextPadding
import top.southfish.engrave.ui.vocabViewModel
import top.southfish.engrave.update.UpdateChecker

/**
 * 「其他」设置：从背单词设置里拆出的低频项——详情页释义显示、单词发音、
 * 检查更新。
 *
 * 单词发音仅控制学习卡片展示英文词头时是否自动朗读，与详情页无关
 * （详情页点词头朗读始终可用）。系统缺英文 TTS 语音数据时禁止开启，
 * 并在下方给出灰色提示。
 *
 * 检查更新只在点击时联网（不自动检测），查 GitHub Releases（含预发行版），
 * 只认本端包（端别标签见 [AppReleaseInfo]，带库版精确到本机 ABI）；
 * 发现新版本显示更新说明，下载交给浏览器。
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
            style = MaterialTheme.typography.body2,
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
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)
    val timeVisible = rememberTimeTextVisible(listState)

    // 检查更新：点击才联网，结果留在页面上直到下次点按重查
    var updateResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        timeText = { AnimatedTimeText(timeVisible) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
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
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
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
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
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
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }

            item {
                Text(
                    "检查更新",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
            item {
                Chip(
                    label = { Text(updateStatusLabel(updateResult, checking)) },
                    onClick = {
                        if (!checking) {
                            checking = true
                            scope.launch {
                                updateResult = UpdateChecker(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    assetTag = AppReleaseInfo.assetTag(context)
                                ).check()
                                checking = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
            when (val result = updateResult) {
                is UpdateChecker.Result.UpdateAvailable -> {
                    item {
                        Text(
                            "最新版 ${result.version}",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        )
                    }
                    if (result.notes.isNotBlank()) {
                        item {
                            Text(
                                result.notes,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                                    )
                                }
                            },
                            modifier = Modifier
                                .width(150.dp)
                                .padding(top = 4.dp)
                        ) {
                            Text("去下载")
                        }
                    }
                }

                is UpdateChecker.Result.NetworkError -> {
                    item {
                        Text(
                            "网络连接失败（${result.detail}）",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}

/** 检查更新那行的文案：空闲提示、检查中、结果各一种。 */
private fun updateStatusLabel(result: UpdateChecker.Result?, checking: Boolean): String = when {
    checking -> "检查中…"
    result is UpdateChecker.Result.UpToDate -> "已是最新版"
    result is UpdateChecker.Result.NetworkError -> "网络错误 · 点按重试"
    result is UpdateChecker.Result.UpdateAvailable -> "发现新版本 ${result.version}"
    else -> "检查是否有新版本"
}
