package top.southfish.engrave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import top.southfish.engrave.ui.navigation.VocabNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 消费进程级冷启动标志：进程重启过 = 冷启动（从主页开始）；
        // 进程还在 = 热启动（导航由内存状态 / DataStore 恢复到上次页面）
        val coldStart = VocabApplication.isColdStart
        VocabApplication.isColdStart = false
        setContent {
            VocabNavHost(coldStart = coldStart)
        }
    }
}
