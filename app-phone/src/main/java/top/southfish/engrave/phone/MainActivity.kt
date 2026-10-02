package top.southfish.engrave.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import top.southfish.engrave.PhoneApplication
import top.southfish.engrave.ui.navigation.PhoneNavHost

/**
 * 非 Wear 手表版入口：与 Wear 版 MainActivity 逻辑一致（冷启动标志消费），
 * 挂载 M3 主题 + 标准 NavHost 的导航骨架。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 消费进程级冷启动标志：进程重启过 = 冷启动（从主页开始）；
        // 进程还在 = 热启动（导航由内存状态 / DataStore 恢复到上次页面）
        val coldStart = PhoneApplication.isColdStart
        PhoneApplication.isColdStart = false
        setContent {
            PhoneNavHost(coldStart = coldStart)
        }
    }
}
