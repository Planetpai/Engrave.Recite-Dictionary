package top.southfish.engrave

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.southfish.engrave.reminder.ReminderIntents
import top.southfish.engrave.ui.components.RotaryInput
import top.southfish.engrave.ui.navigation.VocabNavHost

class MainActivity : ComponentActivity() {

    // 通知/Tile 点进来的「直达学习页」请求；导航层消费后回调清零
    private var autoStartStudy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 消费进程级冷启动标志：进程重启过 = 冷启动（从主页开始）；
        // 进程还在 = 热启动（导航由内存状态 / DataStore 恢复到上次页面）
        val coldStart = VocabApplication.isColdStart
        VocabApplication.isColdStart = false
        consumeDeepLink(intent)
        setContent {
            VocabNavHost(
                coldStart = coldStart,
                autoStartStudy = autoStartStudy,
                onAutoStartConsumed = { autoStartStudy = false }
            )
        }
    }

    // singleTask：应用还活着时点通知不走 onCreate，走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)
    }

    // 表圈滚动的兜底入口：事件沿视图树派发无人消费时落到这里，
    // 交给当前页面注册的 rotary handler（不依赖焦点，绕开 listener 单槽位）
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (RotaryInput.dispatch(event)) return true
        return super.onGenericMotionEvent(event)
    }

    private fun consumeDeepLink(intent: Intent?) {
        if (intent?.getBooleanExtra(ReminderIntents.EXTRA_AUTO_START_STUDY, false) == true) {
            autoStartStudy = true
        }
    }
}
