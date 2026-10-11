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
    /** 「其他」设置：详情页释义、单词发音（从背单词设置拆出的低频项）。 */
    const val OTHER_SETTINGS = "other_settings"
    const val DATA = "data"
    const val MISTAKES = "mistakes"
    const val PICK_FILE = "pick_file"
    /** 从设备文件导入整包数据备份（数据管理页入口，区别于词书导入）。 */
    const val PICK_FILE_BACKUP = "pick_file_backup"
    const val MISTAKES_DRILL = "mistakes_drill"

    /** 使用指引：卡片手势说明，「我的」页进入。 */
    const val TUTORIAL = "tutorial"

    /** 头像照片选择（应用内 MediaStore 浏览器，绕开手表上不可用的系统选图器）。 */
    const val AVATAR_PICKER = "avatar_picker"

    /**
     * 词详情，携带词头。DETAIL 与 DETAIL_ALT 内容相同、成对交替使用：
     * wear-compose-navigation 1.2.1 的 SwipeDismissableNavHost 用路由 pattern
     * 作 SwipeToDismissBox 的 key，同 pattern 相邻入栈（详情→详情）时侧滑
     * 返回会状态错乱连弹两层。详情页之间跳转时交替使用两个 pattern，
     * 保证相邻页 key 不同，侧滑逐层正常返回。
     */
    const val DETAIL = "detail/{term}"
    const val DETAIL_ALT = "detail2/{term}"
    fun detail(term: String) = "detail/${Uri.encode(term)}"
    fun detailAlt(term: String) = "detail2/${Uri.encode(term)}"
}
