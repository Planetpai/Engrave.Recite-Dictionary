package com.example.vocabwatch

import android.app.Application
import android.content.Context
import com.example.vocabwatch.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VocabApplication : Application() {

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
    }
}

/** 在任何能拿到 Context 的地方取依赖容器。 */
val Context.appContainer: AppContainer
    get() = (applicationContext as VocabApplication).container
