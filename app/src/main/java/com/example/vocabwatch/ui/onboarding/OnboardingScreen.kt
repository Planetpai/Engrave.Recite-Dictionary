package com.example.vocabwatch.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.vocabwatch.ui.components.NumberWheel
import com.example.vocabwatch.ui.components.ReminderWheels
import com.example.vocabwatch.ui.components.rememberRotaryScrollable
import com.example.vocabwatch.ui.settings.reminderLabel
import com.example.vocabwatch.ui.vocabViewModel

/**
 * 首启引导，四步：1 选词书 → 2 每天背几个 → 3 每天几点提醒（可跳过）
 * → 4 完成。进度条之外没有花活，每步一屏，下一步/上一步按钮推进。
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = vocabViewModel<OnboardingViewModel> { container ->
        OnboardingViewModel(
            settingsRepository = container.settingsRepository,
            wordRepository = container.wordRepository,
            appContext = container.appContext
        )
    }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    val context = androidx.compose.ui.platform.LocalContext.current
    var openNotificationSettings by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) openNotificationSettings = true }

    if (openNotificationSettings) {
        LaunchedEffect(Unit) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            openNotificationSettings = false
        }
    }
    var wheelHour by mutableIntStateOf(state.reminderMinutes?.let { it / 60 } ?: 8)
    var wheelMinute by mutableIntStateOf(state.reminderMinutes?.let { (it % 60) / 5 * 5 } ?: 0)

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Crossfade(targetState = state.step, label = "onboarding-step") { step ->
            ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item {
                    ListHeader { Text("配置 ${step + 1}/4 · ${stepTitle(step)}") }
                }

                when (step) {
                    0 -> {
                        items(state.books, key = { it.id }) { book ->
                            val selected = state.selectedBookId == book.id
                            Chip(
                                label = { Text(book.name) },
                                secondaryLabel = { Text("${book.wordCount} 词") },
                                onClick = { viewModel.selectBook(book.id) },
                                colors = ChipDefaults.chipColors(
                                    backgroundColor = if (selected) {
                                        MaterialTheme.colors.primary
                                    } else {
                                        MaterialTheme.colors.surface
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                            )
                        }
                        if (state.books.isEmpty()) {
                            item {
                                Text(
                                    "词书准备中…",
                                    style = MaterialTheme.typography.body2,
                                    color = MaterialTheme.colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        item { NextButton(state.canNext, viewModel::next) }
                    }

                    1 -> {
                        item {
                            Text(
                                "每天背几个新词？",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 与设置页一致的转轮，拖动选择
                            NumberWheel(
                                values = (5..50).toList(),
                                selected = state.dailyNewLimit.coerceIn(5, 50),
                                onSelected = viewModel::setDailyNew,
                                format = { "%d 个".format(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item { NextButton(true, viewModel::next) }
                    }

                    2 -> {
                        item {
                            Text(
                                "每天几点提醒你？",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Text(
                                text = state.reminderMinutes
                                    ?.let { "已开启 · ${reminderLabel(it)}" }
                                    ?: "未开启（拖动时间后点开启）",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            ReminderWheels(
                                hour = wheelHour,
                                minute = wheelMinute,
                                onHourChange = { wheelHour = it },
                                onMinuteChange = { wheelMinute = it }
                            )
                        }
                        item {
                            val enabled = state.reminderMinutes != null
                            Button(
                                onClick = {
                                    if (enabled) {
                                        viewModel.setReminder(null)
                                    } else {
                                        viewModel.setReminder(wheelHour * 60 + wheelMinute)
                                        if (Build.VERSION.SDK_INT >= 33) {
                                            if (openNotificationSettings) {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                )
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
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
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) { Text(if (enabled) "关闭提醒" else "开启提醒") }
                        }
                        item { NextButton(true, viewModel::next) }
                    }

                    else -> {
                        item {
                            Text(
                                "都设置好了！\n\n${state.books.firstOrNull { it.id == state.selectedBookId }?.name ?: ""}\n每天 ${state.dailyNewLimit} 个新词" +
                                    (state.reminderMinutes?.let { " · ${reminderLabel(it)} 提醒" } ?: ""),
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                        item {
                            Button(
                                onClick = { viewModel.finish(onDone) },
                                enabled = !state.saving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) { Text("开始使用") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextButton(enabled: Boolean, onNext: () -> Unit) {
    Button(
        onClick = onNext,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) { Text("下一步") }
}

private fun stepTitle(step: Int): String = when (step) {
    0 -> "选词书"
    1 -> "每日数量"
    2 -> "提醒时间"
    else -> "完成"
}
