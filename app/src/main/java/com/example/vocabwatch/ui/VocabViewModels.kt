package com.example.vocabwatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vocabwatch.appContainer
import com.example.vocabwatch.di.AppContainer

/** 取当前进程的依赖容器。 */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { context.appContainer }
}

/**
 * 手动 DI 下的 ViewModel 获取方式。
 *
 * ViewModel 仍由 androidx 的 ViewModelStore 管理（配置变更、返回栈都正常），
 * 只是构造参数从 AppContainer 里取，不需要 Hilt 的 @HiltViewModel。
 *
 * 必须是 inline + reified：androidx 的 viewModel() 本身是 reified 的，
 * 需要具体类型才能从 ViewModelStore 里按 key 存取。
 */
@Composable
inline fun <reified VM : ViewModel> vocabViewModel(
    noinline creator: (AppContainer) -> VM
): VM {
    val container = rememberAppContainer()
    // 不包 remember：Compose 编译器禁止把 noinline 参数捕获进 remember 的 lambda。
    // 每次组合新建这个轻量匿名对象没有代价——viewModel() 只在
    // ViewModelStore 里还没有该 VM 时才会调用工厂。
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            creator(container) as T
    }
    return viewModel(factory = factory)
}
