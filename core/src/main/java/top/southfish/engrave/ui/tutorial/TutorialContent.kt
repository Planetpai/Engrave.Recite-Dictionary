package top.southfish.engrave.ui.tutorial

import top.southfish.engrave.domain.model.Word

/**
 * 交互式使用指引的共享定义：步骤、文案、演示数据。
 *
 * 教程是纯演示：所有数据为内置假词，不碰数据库、不碰 ViewModel。
 * 双端 UI（app / app-phone）各一份 TutorialScreen，共用这里的文案与数据，
 * 改文案只动这一个文件。
 */
enum class TutorialStep(val title: String, val subtitle: String) {
    FLIP("点一下卡片", "学习时先看单词，点一下翻面看释义"),
    SWIPE_LEFT("向左滑 = 会", "翻面确认后左滑；浏览新词时左滑 = 已学过"),
    SWIPE_UP("向上滑 = 不会", "不会的词沉底重来；浏览新词时上滑 = 加入待背"),
    SPELLING("拼出这个单词", "每个词过三遍：英→中→拼写"),
    LONG_PRESS("长按卡片", "难词长按加入错题集，随时回顾"),
    UNDO("双击空白 = 撤销", "先把卡片滑走，再双击卡片外的空白处"),
    SEARCH("搜索单词和词组", "结果分左右两列，滑动切换；点条目看详情"),
    DETAIL("点标题听发音", "详情页里词形、派生词、词组都能点开"),
    DONE("", "");

    companion object {
        /** 操作步总数（不含 DONE 完成页），用于进度显示「n/8」 */
        const val ACTION_COUNT = 8
    }
}

/** 教程演示词。id/bookId 为负，与真实词库隔离。 */
object TutorialDemo {
    val apple = Word(
        id = -1L,
        bookId = -1L,
        term = "apple",
        meaning = "n. 苹果",
        phonetic = "ˈæpəl",
        example = "An apple a day keeps the doctor away.",
        exampleTranslation = "一天一苹果，医生远离我。"
    )

    val banana = Word(
        id = -2L,
        bookId = -1L,
        term = "banana",
        meaning = "n. 香蕉",
        phonetic = "bəˈnɑːnə",
        example = "I eat a banana every morning.",
        exampleTranslation = "我每天早上吃一根香蕉。"
    )

    /** 拼写卡：apple 逐字母，乱序中两个 p 位置打乱 */
    val spellAnswer = listOf("a", "p", "p", "l", "e")
    val spellScrambled = listOf("p", "l", "a", "e", "p")

    /** 仿真搜索结果：单词列 / 词组列 */
    val searchWords = listOf("apple" to "/ˈæpəl/", "application" to "/ˌæplɪˈkeɪʃn/")
    val searchPhrases = listOf("apple pie" to "苹果派")

    /** 完成页手势速查（保留原静态版使用指引的全部信息） */
    val cheatsheet = listOf(
        "点卡片 = 翻面看释义",
        "左滑 = 会（浏览时 = 已学过）",
        "上滑 = 不会（浏览时 = 加入待背）",
        "长按卡片 = 加入错题集",
        "双击空白 = 撤销上一张",
        "点详情标题 = 发音"
    )
}
