package top.southfish.engrave.reminder

/**
 * 直达学习页的 Intent 约定，core 与双端 app 共用。
 *
 * 通知（ReminderReceiver）和 Tile 的点击 Intent 都带上这个 extra；
 * 双端 MainActivity 检测到它就把「直达学习页」请求交给导航层处理。
 */
object ReminderIntents {
    const val EXTRA_AUTO_START_STUDY = "top.southfish.engrave.extra.AUTO_START_STUDY"
}
