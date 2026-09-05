package com.example.vocabwatch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.vocabwatch.ui.home.HomeScreen
import com.example.vocabwatch.ui.stats.StatsScreen
import com.example.vocabwatch.ui.study.StudyScreen

/**
 * 应用导航骨架。
 *
 * 用 SwipeDismissableNavHost 而不是普通 NavHost：Wear OS 的返回手势是
 * 从左边缘右滑，只有它能把这个手势和返回栈联动起来。
 */
@Composable
fun VocabNavHost(modifier: Modifier = Modifier) {
    val navController = rememberSwipeDismissableNavController()

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Destinations.HOME,
            modifier = modifier
        ) {
            composable(Destinations.HOME) {
                HomeScreen(
                    onStartStudy = { navController.navigate(Destinations.STUDY) },
                    onOpenStats = { navController.navigate(Destinations.STATS) }
                )
            }
            composable(Destinations.STUDY) {
                StudyScreen()
            }
            composable(Destinations.STATS) {
                StatsScreen()
            }
        }
    }
}
