package com.example.vocabwatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.example.vocabwatch.ui.home.HomeScreen
import com.example.vocabwatch.ui.stats.StatsScreen
import com.example.vocabwatch.ui.study.StudyScreen

/**
 * 应用导航骨架。
 *
 * 用 SwipeDismissableNavHost 而不是普通 NavHost：Wear OS 的返回手势是
 * 从左边缘右滑，只有它能把这个手势和返回栈联动起来。
 *
 * 学习页要单独把侧滑返回关掉：那一页的卡片本身就在接收左右滑动评分，
 * 系统返回手势会把它抢走。1.2.1 的 navController 还没有 enableSwipeToDismiss，
 * 所以从 SwipeToDismissBoxState 的 confirmStateChange 拦——返回 false 时
 * 滑动不会落到 Dismissed，也就不会 popBackStack，返回键照常可用。
 */
@Composable
fun VocabNavHost(modifier: Modifier = Modifier) {
    val navController = rememberSwipeDismissableNavController()
    var swipeToDismissEnabled by remember { mutableStateOf(true) }
    val hostState = rememberSwipeDismissableNavHostState(
        rememberSwipeToDismissBoxState(confirmStateChange = { swipeToDismissEnabled })
    )

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Destinations.HOME,
            state = hostState,
            modifier = modifier
        ) {
            composable(Destinations.HOME) {
                HomeScreen(
                    onStartStudy = { navController.navigate(Destinations.STUDY) },
                    onOpenStats = { navController.navigate(Destinations.STATS) }
                )
            }
            composable(Destinations.STUDY) {
                DisposableEffect(Unit) {
                    swipeToDismissEnabled = false
                    onDispose { swipeToDismissEnabled = true }
                }
                StudyScreen()
            }
            composable(Destinations.STATS) {
                StatsScreen()
            }
        }
    }
}
