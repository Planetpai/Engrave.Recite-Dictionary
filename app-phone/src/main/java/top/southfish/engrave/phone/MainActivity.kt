package top.southfish.engrave.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.southfish.engrave.PhoneApplication
import top.southfish.engrave.reminder.ReminderIntents
import top.southfish.engrave.ui.navigation.PhoneNavHost

/**
 * 非 Wear 手表版入口：与 Wear 版 MainActivity 逻辑一致（冷启动标志消费 +
 * 通知/Tile 直达学习页），挂载 M3 主题 + 标准 NavHost 的导航骨架。
 */
class MainActivity : ComponentActivity() {

    // 通知点进来的「直达学习页」请求；导航层消费后回调清零
    private var autoStartStudy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 消费进程级冷启动标志：进程重启过 = 冷启动（从主页开始）；
        // 进程还在 = 热启动（导航由内存状态 / DataStore 恢复到上次页面）
        val coldStart = PhoneApplication.isColdStart
        PhoneApplication.isColdStart = false
        consumeDeepLink(intent)
        setContent {
            PhoneNavHost(
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

    private fun consumeDeepLink(intent: Intent?) {
        if (intent?.getBooleanExtra(ReminderIntents.EXTRA_AUTO_START_STUDY, false) == true) {
            autoStartStudy = true
        }
    }
}
