package top.southfish.engrave.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBoxState
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.ui.books.BooksScreen
import top.southfish.engrave.ui.books.FilePickerScreen
import top.southfish.engrave.ui.data.DataScreen
import top.southfish.engrave.ui.home.HomeScreen
import top.southfish.engrave.ui.mistakes.MistakesDrillScreen
import top.southfish.engrave.ui.mistakes.MistakesScreen
import top.southfish.engrave.ui.onboarding.OnboardingScreen
import top.southfish.engrave.ui.profile.AvatarPickerScreen
import top.southfish.engrave.ui.rememberAppContainer
import top.southfish.engrave.ui.search.SearchScreen
import top.southfish.engrave.ui.search.WordDetailScreen
import top.southfish.engrave.ui.settings.StudySettingsScreen
import top.southfish.engrave.ui.stats.StatsScreen
import top.southfish.engrave.ui.study.StudyScreen
import top.southfish.engrave.ui.tutorial.TutorialScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * 应用导航骨架。
 *
 * 用 SwipeDismissableNavHost 而不是普通 NavHost：Wear OS 的返回手势是
 * 从左边缘右滑，只有它能把这个手势和返回栈联动起来。
 *
 * 学习页要单独把侧滑返回关掉：那一页的卡片本身就在接收左右滑动评分，
 * 系统返回手势会把它抢走。
 *
 * 启动页由「首启引导是否完成」决定：DataStore 还没读到时先空一帧，
 * 读到后按结果挂载不同的导航图（key 保证 startDestination 正确）。
 *
 * **应用状态记忆**：完成过引导后，每次返回栈变化都会把路由序列写进
 * DataStore。热启动（后台进程未被终止）时恢复到上次退出的页面；
 * 冷启动（进程被杀后重开，由 MainActivity 传入的 coldStart 判定）
 * 一律从主页开始，不恢复旧栈——记录器会立刻用 [HOME] 覆盖旧数据。
 */
@Composable
fun VocabNavHost(
    coldStart: Boolean,
    modifier: Modifier = Modifier
) {
    val container = rememberAppContainer()
    val onboardingDone by container.settingsRepository.onboardingDone.collectAsState(initial = null)
    val startKnown = onboardingDone ?: return Box(modifier.fillMaxSize().background(MaterialTheme.colors.background))

    key(startKnown) {
        AppNavHost(
            startDestination = if (startKnown) Destinations.HOME else Destinations.ONBOARDING,
            // 引导本身不记栈；完成后（start 变为 HOME）才开始记录和恢复。
            // 冷启动只记录、不恢复：保证从主页开始。
            recordNavStack = startKnown,
            restoreStack = startKnown && !coldStart,
            settingsRepository = container.settingsRepository,
            modifier = modifier
        )
    }
}

/** 把栈条目还原成可跳转的路由串（detail/{term} 用实际词头拼回去）。 */
private fun NavBackStackEntry.toRouteString(): String {
    val pattern = destination.route ?: return Destinations.HOME
    when (pattern) {
        Destinations.DETAIL -> return Destinations.detail(arguments?.getString("term").orEmpty())
        Destinations.DETAIL_ALT -> return Destinations.detailAlt(arguments?.getString("term").orEmpty())
    }
    return pattern
}

@Composable
private fun AppNavHost(
    startDestination: String,
    recordNavStack: Boolean,
    restoreStack: Boolean,
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberSwipeDismissableNavController()
    var swipeToDismissEnabled by remember { mutableStateOf(true) }
    val hostState = rememberSwipeDismissableNavHostState(
        rememberSwipeToDismissBoxState(confirmStateChange = { swipeToDismissEnabled })
    )

    // 恢复：热启动（进程未被终止后重进，如系统回收了 Activity）时，把
    // 上次退出的页面一层层推回去（首项 HOME 已是起点）。冷启动跳过，
    // 保证从主页开始。先等首条栈条目出现（graph 就绪的信号），再导航，
    // 避免过早 navigate 抛异常。旧格式数据里可能有导航包装层产生的
    // 连续重复路由，先压缩再导航。
    LaunchedEffect(startDestination, restoreStack) {
        if (!restoreStack) return@LaunchedEffect
        val saved = settingsRepository.navStack.firstOrNull()
        if (saved.isNullOrEmpty() || saved.size < 2) return@LaunchedEffect
        val routes = saved.filterIndexed { i, r -> i == 0 || r != saved[i - 1] }
        navController.currentBackStackEntryFlow.first()
        routes.drop(1).forEach { route ->
            runCatching { navController.navigate(route) }
        }
    }

    // 记录：跟随当前栈顶变化，在本地维护一条干净的逻辑路由栈后落盘。
    // Wear 的导航实现里一个页面会带一层同路由的包装条目（连续重复），
    // 不能直接把内部栈写盘——这里按「重复=同一页、回到旧路由=出栈、
    // 新路由=压栈」对账，无论应用何时被杀，盘上都有最新现场。
    // 栈底预置 startDestination：恢复导航先于本收集器启动时，首条
    // emission 可能已是恢复出的栈顶，从空表开始记会把栈序记反。
    LaunchedEffect(startDestination, recordNavStack) {
        if (!recordNavStack) return@LaunchedEffect
        val recorded = mutableListOf(startDestination)
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.toRouteString()
            when {
                recorded.last() == route -> return@collect
                else -> {
                    val idx = recorded.indexOf(route)
                    if (idx >= 0) {
                        while (recorded.size > idx + 1) recorded.removeAt(recorded.size - 1)
                    } else {
                        recorded.add(route)
                    }
                }
            }
            settingsRepository.setNavStack(recorded.toList())
        }
    }

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = startDestination,
            state = hostState,
            modifier = modifier
        ) {
            composable(Destinations.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(Destinations.HOME) {
                            popUpTo(Destinations.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Destinations.HOME) {
                HomeScreen(
                    onStartStudy = { navController.navigate(Destinations.STUDY) },
                    onOpenSearch = { navController.navigate(Destinations.SEARCH) },
                    onOpenStats = { navController.navigate(Destinations.STATS) },
                    onOpenBooks = { navController.navigate(Destinations.BOOKS) },
                    onOpenSettings = { navController.navigate(Destinations.STUDY_SETTINGS) },
                    onOpenData = { navController.navigate(Destinations.DATA) },
                    onOpenMistakes = { navController.navigate(Destinations.MISTAKES) },
                    onPickAvatar = { navController.navigate(Destinations.AVATAR_PICKER) },
                    onOpenTutorial = { navController.navigate(Destinations.TUTORIAL) }
                )
            }

            composable(Destinations.STUDY) {
                DisposableEffect(Unit) {
                    swipeToDismissEnabled = false
                    onDispose { swipeToDismissEnabled = true }
                }
                StudyScreen(onExit = { navController.popBackStack() })
            }

            composable(Destinations.STATS) {
                StatsScreen()
            }

            composable(Destinations.SEARCH) {
                SearchScreen(
                    onOpenWord = { term ->
                        navController.navigate(Destinations.detail(term))
                    }
                )
            }

            composable(Destinations.DETAIL) { entry ->
                val term = entry.arguments?.getString("term").orEmpty()
                WordDetailScreen(
                    term = term,
                    // 详情→详情用 DETAIL_ALT，保证相邻页 pattern 不同（见 Destinations 注释）
                    onOpenWord = { t -> navController.navigate(Destinations.detailAlt(t)) }
                )
            }

            composable(Destinations.DETAIL_ALT) { entry ->
                val term = entry.arguments?.getString("term").orEmpty()
                WordDetailScreen(
                    term = term,
                    onOpenWord = { t -> navController.navigate(Destinations.detail(t)) }
                )
            }

            composable(Destinations.BOOKS) {
                BooksScreen(
                    onPickFile = { navController.navigate(Destinations.PICK_FILE) }
                )
            }

            composable(Destinations.PICK_FILE) {
                FilePickerScreen(
                    mode = top.southfish.engrave.ui.books.PickerMode.BOOK,
                    onImported = { navController.popBackStack() }
                )
            }

            composable(Destinations.PICK_FILE_BACKUP) {
                FilePickerScreen(
                    mode = top.southfish.engrave.ui.books.PickerMode.BACKUP,
                    onImported = { navController.popBackStack() }
                )
            }

            composable(Destinations.MISTAKES_DRILL) {
                DisposableEffect(Unit) {
                    swipeToDismissEnabled = false
                    onDispose { swipeToDismissEnabled = true }
                }
                MistakesDrillScreen(onExit = { navController.popBackStack() })
            }

            composable(Destinations.STUDY_SETTINGS) {
                StudySettingsScreen()
            }

            composable(Destinations.TUTORIAL) {
                TutorialScreen()
            }

            composable(Destinations.DATA) {
                DataScreen(
                    onPickFile = { navController.navigate(Destinations.PICK_FILE_BACKUP) }
                )
            }

            composable(Destinations.MISTAKES) {
                MistakesScreen(
                    onOpenWord = { term ->
                        navController.navigate(Destinations.detail(term))
                    },
                    onStartDrill = { navController.navigate(Destinations.MISTAKES_DRILL) }
                )
            }

            composable(Destinations.AVATAR_PICKER) {
                AvatarPickerScreen(
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
