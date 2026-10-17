package top.southfish.engrave.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.ui.books.BooksScreen
import top.southfish.engrave.ui.books.FilePickerScreen
import top.southfish.engrave.ui.components.EngravePhoneTheme
import top.southfish.engrave.ui.data.DataScreen
import top.southfish.engrave.ui.home.HomeScreen
import top.southfish.engrave.ui.mistakes.MistakesDrillScreen
import top.southfish.engrave.ui.mistakes.MistakesScreen
import top.southfish.engrave.ui.onboarding.OnboardingScreen
import top.southfish.engrave.ui.profile.AvatarPickerScreen
import top.southfish.engrave.ui.rememberAppContainer
import top.southfish.engrave.ui.search.SearchScreen
import top.southfish.engrave.ui.search.WordDetailScreen
import top.southfish.engrave.ui.settings.OtherSettingsScreen
import top.southfish.engrave.ui.settings.StudySettingsScreen
import top.southfish.engrave.ui.stats.StatsScreen
import top.southfish.engrave.ui.study.StudyScreen
import top.southfish.engrave.ui.tutorial.TutorialScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/**
 * 非 Wear 手表版的导航骨架：标准 navigation-compose + 系统返回。
 * 与 Wear 版的差异只有容器（SwipeDismissableNavHost → NavHost）和
 * 学习页的「关侧滑返回」逻辑——系统返回手势与卡片评分手势天然不冲突，
 * 该开关不再需要。导航栈的记录/恢复逻辑与 Wear 版逐行一致。
 */
@Composable
fun PhoneNavHost(
    coldStart: Boolean,
    autoStartStudy: Boolean = false,
    onAutoStartConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = rememberAppContainer()
    val onboardingDone by container.settingsRepository.onboardingDone.collectAsState(initial = null)
    val startKnown = onboardingDone ?: return Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))

    key(startKnown) {
        AppNavHost(
            startDestination = if (startKnown) Destinations.HOME else Destinations.ONBOARDING,
            // 引导本身不记栈；完成后（start 变为 HOME）才开始记录和恢复。
            // 冷启动只记录、不恢复：保证从主页开始。
            recordNavStack = startKnown,
            restoreStack = startKnown && !coldStart,
            autoStartStudy = autoStartStudy,
            onAutoStartConsumed = onAutoStartConsumed,
            settingsRepository = container.settingsRepository,
            modifier = modifier
        )
    }
}

/** 把栈条目还原成可跳转的路由串（detail/{term} 用实际词头拼回去）。 */
private fun NavBackStackEntry.toRouteString(): String {
    val pattern = destination.route ?: return Destinations.HOME
    if (pattern == Destinations.DETAIL) {
        return Destinations.detail(arguments?.getString("term").orEmpty())
    }
    return pattern
}

@Composable
private fun AppNavHost(
    startDestination: String,
    recordNavStack: Boolean,
    restoreStack: Boolean,
    autoStartStudy: Boolean,
    onAutoStartConsumed: () -> Unit,
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    // 恢复逻辑完成后置 true：直达导航要等它，避免把学习页压在恢复栈下面
    var navReady by remember { mutableStateOf(false) }

    // 恢复：热启动（进程未被终止后重进，如系统回收了 Activity）时，把
    // 上次退出的页面一层层推回去（首项 HOME 已是起点）。冷启动跳过，
    // 保证从主页开始。先等首条栈条目出现（graph 就绪的信号），再导航，
    // 避免过早 navigate 抛异常。旧格式数据里可能有连续重复路由，先压缩再导航。
    // 无论走没走恢复，最后都放行 navReady。
    LaunchedEffect(startDestination, restoreStack) {
        if (restoreStack) {
            val saved = settingsRepository.navStack.firstOrNull()
            if (!saved.isNullOrEmpty() && saved.size >= 2) {
                val routes = saved.filterIndexed { i, r -> i == 0 || r != saved[i - 1] }
                navController.currentBackStackEntryFlow.first()
                routes.drop(1).forEach { route ->
                    runCatching { navController.navigate(route) }
                }
            }
        }
        navReady = true
    }

    // 直达：通知点进来（extra 标记）时直接进学习页，自带断点续背。
    // 键上 navReady 等恢复走完再导航；launchSingleTop 防止已在学习页时叠两层；
    // 引导未完成（start 不是 HOME）时只消费不导航。
    LaunchedEffect(autoStartStudy, navReady) {
        if (!autoStartStudy || !navReady) return@LaunchedEffect
        onAutoStartConsumed()
        if (startDestination == Destinations.HOME) {
            navController.currentBackStackEntryFlow.first()
            runCatching {
                navController.navigate(Destinations.STUDY) { launchSingleTop = true }
            }
        }
    }

    // 记录：跟随当前栈顶变化，在本地维护一条干净的逻辑路由栈后落盘。
    // 按「重复=同一页、回到旧路由=出栈、新路由=压栈」对账，无论应用何时
    // 被杀，盘上都有最新现场。
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

    EngravePhoneTheme {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier
        ) {
            composable(Destinations.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(Destinations.HOME) {
                            popUpTo(Destinations.ONBOARDING) { inclusive = true }
                        }
                    },
                    onOpenData = { navController.navigate(Destinations.DATA) }
                )
            }

            composable(Destinations.HOME) {
                HomeScreen(
                    onStartStudy = { navController.navigate(Destinations.STUDY) },
                    onOpenSearch = { navController.navigate(Destinations.SEARCH) },
                    onOpenStats = { navController.navigate(Destinations.STATS) },
                    onOpenBooks = { navController.navigate(Destinations.BOOKS) },
                    onOpenSettings = { navController.navigate(Destinations.STUDY_SETTINGS) },
                    onOpenOtherSettings = { navController.navigate(Destinations.OTHER_SETTINGS) },
                    onOpenData = { navController.navigate(Destinations.DATA) },
                    onOpenMistakes = { navController.navigate(Destinations.MISTAKES) },
                    onPickAvatar = { navController.navigate(Destinations.AVATAR_PICKER) },
                    onOpenTutorial = { navController.navigate(Destinations.TUTORIAL) }
                )
            }

            composable(Destinations.STUDY) {
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
                MistakesDrillScreen(onExit = { navController.popBackStack() })
            }

            composable(Destinations.STUDY_SETTINGS) {
                StudySettingsScreen()
            }

            composable(Destinations.OTHER_SETTINGS) {
                OtherSettingsScreen()
            }

            composable(Destinations.TUTORIAL) {
                TutorialScreen(onDone = { navController.popBackStack() })
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
