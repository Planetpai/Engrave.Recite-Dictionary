package top.southfish.engrave

import android.app.Application
import android.content.Context
import top.southfish.engrave.data.repository.REMINDER_MINUTES_KEY
import top.southfish.engrave.data.repository.settingsDataStore
import top.southfish.engrave.di.AppContainer
import top.southfish.engrave.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VocabApplication : Application() {

    companion object {
        /**
         * 进程级冷启动标志：随进程创建初始化为 true，MainActivity 首次
         * onCreate 消费后置 false。进程还活着的热启动不会再执行 Application
         * 构造，标志保持 false —— 以此区分「冷启动（进程被杀后重开）」
         * 与「热启动（后台进程未被终止）」。
         */
        var isColdStart: Boolean = true
    }

    lateinit var container: AppContainer
        private set

    /**
     * 生命周期和进程一致的协程作用域，用于首次启动导入词书这类
     * 不属于任何界面的后台工作。SupervisorJob 保证单个任务失败
     * 不会把整个作用域带崩。
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 导入是按 sourceKey 幂等的，已导入过的词书会在事务开头短路返回。
        // 放后台跑，不阻塞冷启动。
        applicationScope.launch {
            runCatching { container.seedDataProvider.ensureSeeded() }
        }

        // 提醒闹钟兜底重排：用户手动把应用划掉（force-stop 会清掉所有闹钟）
        // 后再打开时，趁这次冷启动把闹钟补排回去。BootReceiver 只覆盖重启
        // 和覆盖安装，管不到 force-stop。
        applicationScope.launch {
            runCatching {
                val prefs = settingsDataStore.data.first()
                ReminderScheduler.ensureChannel(this@VocabApplication)
                ReminderScheduler.schedule(this@VocabApplication, prefs[REMINDER_MINUTES_KEY])
            }
        }
    }
}

/** 在任何能拿到 Context 的地方取依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as VocabApplication).container
