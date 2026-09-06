package com.example.vocabwatch.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.SwipeToDismissBoxState
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.example.vocabwatch.ui.books.BooksScreen
import com.example.vocabwatch.ui.books.FilePickerScreen
import com.example.vocabwatch.ui.data.DataScreen
import com.example.vocabwatch.ui.home.HomeScreen
import com.example.vocabwatch.ui.mistakes.MistakesDrillScreen
import com.example.vocabwatch.ui.mistakes.MistakesScreen
import com.example.vocabwatch.ui.onboarding.OnboardingScreen
import com.example.vocabwatch.ui.rememberAppContainer
import com.example.vocabwatch.ui.search.SearchScreen
import com.example.vocabwatch.ui.search.WordDetailScreen
import com.example.vocabwatch.ui.settings.StudySettingsScreen
import com.example.vocabwatch.ui.stats.StatsScreen
import com.example.vocabwatch.ui.study.StudyScreen

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
 */
@Composable
fun VocabNavHost(modifier: Modifier = Modifier) {
    val container = rememberAppContainer()
    val onboardingDone by container.settingsRepository.onboardingDone.collectAsState(initial = null)
    val startKnown = onboardingDone ?: return Box(modifier.fillMaxSize().background(MaterialTheme.colors.background))

    key(startKnown) {
        AppNavHost(
            startDestination = if (startKnown) Destinations.HOME else Destinations.ONBOARDING,
            modifier = modifier
        )
    }
}

@Composable
private fun AppNavHost(startDestination: String, modifier: Modifier = Modifier) {
    val navController = rememberSwipeDismissableNavController()
    var swipeToDismissEnabled by remember { mutableStateOf(true) }
    val hostState = rememberSwipeDismissableNavHostState(
        rememberSwipeToDismissBoxState(confirmStateChange = { swipeToDismissEnabled })
    )

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
                    onOpenMistakes = { navController.navigate(Destinations.MISTAKES) }
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
                WordDetailScreen(term = term)
            }

            composable(Destinations.BOOKS) {
                BooksScreen(
                    onPickFile = { navController.navigate(Destinations.PICK_FILE) }
                )
            }

            composable(Destinations.PICK_FILE) {
                FilePickerScreen()
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

            composable(Destinations.DATA) {
                DataScreen()
            }

            composable(Destinations.MISTAKES) {
                MistakesScreen(
                    onOpenWord = { term ->
                        navController.navigate(Destinations.detail(term))
                    },
                    onStartDrill = { navController.navigate(Destinations.MISTAKES_DRILL) }
                )
            }
        }
    }
}
