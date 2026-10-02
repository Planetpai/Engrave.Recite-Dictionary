package top.southfish.engrave.ui.onboarding

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

import top.southfish.engrave.ui.components.NumberWheel
import top.southfish.engrave.ui.components.ReminderWheels
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.reminder.ReminderScheduler
import top.southfish.engrave.ui.settings.reminderLabel
import top.southfish.engrave.ui.vocabViewModel

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
    val listState = rememberLazyListState()
    rememberRotaryScrollable(listState)

    val context = androidx.compose.ui.platform.LocalContext.current
    var openNotificationSettings by remember { mutableStateOf(false) }

    // 权限状态缓存：从系统设置返回（ON_RESUME）时复检，已授予就不再弹窗/跳设置
    var notificationGranted by remember {
        mutableStateOf(ReminderScheduler.hasNotificationPermission(context))
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationGranted = ReminderScheduler.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
        if (!granted) openNotificationSettings = true
        else ReminderScheduler.maybeRequestIgnoreBatteryOptimizations(context)
    }

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
    // 转轮本地值必须 remember：不包 remember 每次重组都会被重置回已存值，
    // 拖动后实时同步就会弹回
    var wheelHour by remember { mutableIntStateOf(state.reminderMinutes?.let { it / 60 } ?: 8) }
    var wheelMinute by remember { mutableIntStateOf(state.reminderMinutes?.let { (it % 60) / 5 * 5 } ?: 0) }

    Scaffold(
        modifier = modifier,
                    ) {
        Crossfade(targetState = state.step, label = "onboarding-step") { step ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 方形屏顶部要给 TimeText 让位，否则标题/状态行被时间压住
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = top.southfish.engrave.ui.components.timeTextPadding(),
                    bottom = 16.dp
                )
            ) {
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
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                style = MaterialTheme.typography.bodyMedium,
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
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 实时同步：跟着转轮的当前值走，拖动立即看到最新时间
                            Text(
                                text = if (state.reminderMinutes != null) {
                                    "已开启 · ${reminderLabel(wheelHour * 60 + wheelMinute)}"
                                } else {
                                    "未开启（拖动时间后点开启）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
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
                                        // 先查权限：已授予只引导电池白名单，绝不再弹系统
                                        // 请求（部分手表对已授予权限直接回「拒绝」，误跳设置页）；
                                        // 未授予才发请求，被拒后由 launcher 回调带去系统设置页
                                        if (notificationGranted ||
                                            ReminderScheduler.hasNotificationPermission(context)
                                        ) {
                                            notificationGranted = true
                                            ReminderScheduler.maybeRequestIgnoreBatteryOptimizations(context)
                                        } else {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                },
                                colors = if (enabled) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
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
                                style = MaterialTheme.typography.bodyMedium,
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
