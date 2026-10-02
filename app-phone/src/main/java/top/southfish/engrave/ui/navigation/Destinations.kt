package top.southfish.engrave.ui.navigation

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
    /** 从设备文件导入整包数据备份（数据管理页入口，区别于词书导入）。 */
    const val PICK_FILE_BACKUP = "pick_file_backup"
    const val MISTAKES_DRILL = "mistakes_drill"

    /** 头像照片选择（应用内 MediaStore 浏览器，绕开手表上不可用的系统选图器）。 */
    const val AVATAR_PICKER = "avatar_picker"

    /** 词详情，携带词头。 */
    const val DETAIL = "detail/{term}"
    fun detail(term: String) = "detail/${Uri.encode(term)}"
}
