package top.southfish.engrave.ui.settings

import android.Manifest
import android.content.Intent
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
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import top.southfish.engrave.reminder.ReminderScheduler
import top.southfish.engrave.ui.components.NumberWheel
import top.southfish.engrave.ui.components.rememberRotaryScrollable
import top.southfish.engrave.ui.vocabViewModel

fun reminderLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/**
 * 背单词设置：每日新词（转轮拖选）+ 每日提醒（时/分转轮 + 开关按钮）。
 * 详情页释义与单词发音已拆到「其他设置」（OtherSettingsScreen）。
 */
@Composable
fun StudySettingsScreen(modifier: Modifier = Modifier) {
    val viewModel = vocabViewModel<StudySettingsViewModel> { container ->
        StudySettingsViewModel(
            settingsRepository = container.settingsRepository,
            appContext = container.appContext,
            ttsSpeaker = container.ttsSpeaker
        )
    }
    val state by viewModel.uiState.collectAsState()

    // 系统弹窗被拒（含「不再询问」）后，改跳应用的通知设置页作为二次引导；
    // 无论授没授予，都顺带引导一次电池优化白名单，防止后台清理吞掉闹钟
    val context = LocalContext.current
    var openNotificationSettings by remember { mutableStateOf(false) }

    // 权限状态缓存：从系统设置返回（ON_RESUME）时复检，已授予就不再弹窗也不再跳设置
    var notificationGranted by remember {
        mutableStateOf(ReminderScheduler.hasNotificationPermission(context))
    }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
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
        ReminderScheduler.maybeRequestIgnoreBatteryOptimizations(context)
    }

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

    // 开启提醒后的引导链：先查权限——已授予就只引导电池优化白名单，
    // 绝不再弹系统请求（部分手表对已授予的权限直接回「拒绝」，会误跳设置页）；
    // 未授予才发请求，被拒（含「不再询问」）退到应用通知设置页
    val onReminderEnabled = {
        if (notificationGranted || ReminderScheduler.hasNotificationPermission(context)) {
            notificationGranted = true
            ReminderScheduler.maybeRequestIgnoreBatteryOptimizations(context)
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 加载门禁：DataStore 首读完成前只显示占位，不组合任何可写内容。
    // 转轮若在默认态下组合，会把「默认 8:00」当用户输入回写，静默重置
    // 已保存的提醒时间（真实踩过的坑）。门禁必须挡住 remember 声明本身，
    // 所以设置主体抽成独立函数、只在 loaded 后调用。
    if (!state.loaded) {
        Text(
            "读取设置…",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxSize().padding(top = 24.dp)
        )
        return
    }

    StudySettingsContent(
        state = state,
        viewModel = viewModel,
        onReminderEnabled = onReminderEnabled,
        modifier = modifier
    )
}

@Composable
private fun StudySettingsContent(
    state: StudySettingsUiState,
    viewModel: StudySettingsViewModel,
    onReminderEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    rememberRotaryScrollable(listState)

    // 转轮的本地选中值：走到这里 state 必然已加载，初始化值就是真实设置；
    // 提醒关闭时给个可编辑的默认时间。
    var wheelHour by remember { mutableIntStateOf(state.reminderMinutes?.let { it / 60 } ?: 8) }
    var wheelMinute by remember { mutableIntStateOf(state.reminderMinutes?.let { (it % 60) / 5 * 5 } ?: 0) }

    // 自动保存：提醒开着时，转轮一停就把新时间落库并重排闹钟，无需额外按保存
    LaunchedEffect(wheelHour, wheelMinute) {
        val current = state.reminderMinutes
        if (current != null) {
            val minutes = wheelHour * 60 + wheelMinute
            if (minutes != current) viewModel.setReminder(minutes)
        }
    }

    Scaffold(
        modifier = modifier,
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            // 转轮条目很高，自动居中吸附会和它打架导致列表滚不动，这里关掉
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = top.southfish.engrave.ui.components.timeTextPadding(),
                bottom = 16.dp
            )
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
                // 实时同步：跟着转轮的当前值走，拖动立即看到最新时间
                Text(
                    text = if (state.reminderMinutes != null) {
                        "已开启 · ${reminderLabel(wheelHour * 60 + wheelMinute)}"
                    } else {
                        "已关闭 · 选定 ${reminderLabel(wheelHour * 60 + wheelMinute)}"
                    },
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
                        if (!enabled) onReminderEnabled()
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
