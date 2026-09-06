package com.example.vocabwatch.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.domain.model.DefinitionDisplay
import com.example.vocabwatch.ui.components.NumberWheel
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.vocabViewModel

fun reminderLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/**
 * 背单词设置：每日新词（转轮拖选）、详情页释义显示、每日提醒（时/分转轮
 * + 开关按钮）。复习不设上限，所以没有复习上限这一项了。
 */
@Composable
fun StudySettingsScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StudySettingsViewModel> { container ->
        StudySettingsViewModel(
            settingsRepository = container.settingsRepository,
            appContext = container.appContext
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    // 系统弹窗被拒（含「不再询问」）后，改跳应用的通知设置页作为二次引导
    val context = LocalContext.current
    var openNotificationSettings by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) openNotificationSettings = true }

    fun openAppNotificationSettings(context: android.content.Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    if (openNotificationSettings) {
        LaunchedEffect(Unit) { openAppNotificationSettings(context); openNotificationSettings = false }
    }

    // 转轮的本地选中值：提醒关闭时也给个可编辑的默认时间
    var wheelHour by remember { mutableIntStateOf(state.reminderMinutes?.let { it / 60 } ?: 8) }
    var wheelMinute by remember { mutableIntStateOf(state.reminderMinutes?.let { (it % 60) / 5 * 5 } ?: 0) }

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            // 转轮条目很高，自动居中吸附会和它打架导致列表滚不动，这里关掉
            autoCentering = null,
            modifier = Modifier.fillMaxSize()
        ) {
            item { ListHeader { Text("背单词设置") } }

            item {
                Text(
                    "每日新词",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                NumberWheel(
                    values = (5..50).toList(),
                    selected = state.dailyNewLimit.coerceIn(5, 50),
                    onSelected = viewModel::setDailyNew,
                    format = { "%d 个".format(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

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
                    "每日提醒",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
            item {
                Text(
                    text = state.reminderMinutes?.let { "已开启 · ${reminderLabel(it)}" } ?: "已关闭",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    NumberWheel(
                        values = (0..23).toList(),
                        selected = wheelHour,
                        onSelected = { wheelHour = it },
                        format = { "%02d 时".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                    NumberWheel(
                        values = (0..55 step 5).toList(),
                        selected = wheelMinute,
                        onSelected = { wheelMinute = it },
                        format = { "%02d 分".format(it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                val enabled = state.reminderMinutes != null
                Button(
                    onClick = {
                        val minutes = wheelHour * 60 + wheelMinute
                        viewModel.setReminder(if (enabled) null else minutes)
                        if (!enabled && Build.VERSION.SDK_INT >= 33) {
                            if (openNotificationSettings) {
                                openAppNotificationSettings(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                    colors = if (enabled) {
                        ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(if (enabled) "关闭提醒" else "开启提醒")
                }
            }
        }
    }
}
