package com.example.vocabwatch.ui.navigation

import android.net.Uri

/** 导航路由。集中定义，避免各处散落魔法字符串。 */
object Destinations {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val STUDY = "study"
    const val STATS = "stats"
    const val SEARCH = "search"
    const val BOOKS = "books"
    const val STUDY_SETTINGS = "study_settings"
    const val DATA = "data"
    const val MISTAKES = "mistakes"
    const val PICK_FILE = "pick_file"
    const val MISTAKES_DRILL = "mistakes_drill"

    /** 词详情，携带词头。 */
    const val DETAIL = "detail/{term}"
    fun detail(term: String) = "detail/${Uri.encode(term)}"
}
