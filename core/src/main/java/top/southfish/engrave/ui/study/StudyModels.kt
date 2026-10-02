package top.southfish.engrave.ui.study

import top.southfish.engrave.domain.model.StudyItem

/** 滑动方向。既代表用户手势，也代表撤销时卡片该从哪个方向飞回来。 */
enum class SwipeDirection { LEFT, RIGHT, UP }

/**
 * 二元判定。浏览阶段是「已学过 / 不认识」，正式与复习阶段是「会 / 不会」。
 * 手势统一：从右往左滑 = KNOWN（避开 Wear 左缘右滑返回的系统手势），
 * 上滑 = UNKNOWN。
 */
enum class Verdict { KNOWN, UNKNOWN }

/** 卡片考法。拼写卡给乱序字母，另外两种靠翻面自评。 */
enum class CardKind { EN_TO_ZH, ZH_TO_EN, SPELLING }

/** FORMAL 是背今天选出来的新词，REVIEW 是复习到期词。 */
enum class TestPhase { FORMAL, REVIEW }

data class BrowseCard(
    val item: StudyItem
)

data class TestCard(
    /** 会话内唯一。答错重排会产生同一个词的第二张卡，靠它区分 */
    val uid: Long,
    val item: StudyItem,
    val kind: CardKind,
    /** 这是该词在本轮里刷到第几遍，决定考法：1 英文面、2 中文面、3 拼写 */
    val encounter: Int,
    /** 拼写卡按顺序的正确分块（如 dis/a/bi/li/ty），其它卡型为空 */
    val answer: List<String> = emptyList(),
    /** 拼写卡的乱序分块，其它卡型为空 */
    val scrambled: List<String> = emptyList(),
    val flipped: Boolean = false
)

sealed interface StudyUiState {
    data object Loading : StudyUiState

    /** 今天既没有新词也没有到期复习 */
    data object Empty : StudyUiState

    /** 浏览选词：左滑已学过，上滑不认识 */
    data class Browsing(
        val card: BrowseCard,
        val behindCount: Int,
        /** 队列里接下来的词，供叠卡下层预览，不含当前这张 */
        val nextItems: List<StudyItem>,
        /** 含当前这张，还剩多少个词要过一遍 */
        val remaining: Int,
        val selectedCount: Int,
        val selectionTarget: Int,
        val undoReplay: SwipeDirection?
    ) : StudyUiState

    /** 阶段之间的提示页，例如「选够了，开始背单词」 */
    data class Break(
        val title: String,
        val subtitle: String,
        val primaryLabel: String,
        val onPrimary: BreakAction,
        /** null 表示没有第二选择（恢复的会话）。 */
        val secondaryLabel: String? = null,
        val onSecondary: BreakAction? = null
    ) : StudyUiState

    /** Break 页按钮触发的动作。 */
    enum class BreakAction { START_FORMAL, START_REVIEW, CONTINUE_LEARNING, REST }

    /** 正式背单词 / 复习：点一下翻面，然后左滑会、上滑不会 */
    data class Testing(
        val phase: TestPhase,
        val card: TestCard,
        val behindCount: Int,
        /** 队列里接下来的卡，供叠卡下层预览，不含当前这张 */
        val nextCards: List<TestCard>,
        val remaining: Int,
        val undoReplay: SwipeDirection?
    ) : StudyUiState

    data class Finished(
        val newCount: Int,
        val reviewCount: Int,
        val againCount: Int
    ) : StudyUiState
}

private const val SINGLE_LETTER_MAX = 6

/**
 * 拼写卡的分块：只用于拼写，不动单词本体在其它卡面上的显示。
 *
 * 长词拆成「前 3 个字母 + 剩余部分从尾往头每 2 个字母一组」，
 * 例如 disability → dis / a / bi / li / ty。短词（≤[SINGLE_LETTER_MAX]）
 * 保持逐字母。以后接入词典后，可以换成按词根词缀的更聪明的拆法，
 * 调用方（状态机）只认 List<String>，不用改。
 *
 * 从 SpellingPanel.kt（UI 层）迁入 core：唯一调用方 StudyViewModel 在 core，
 * 跨模块 internal 不可见，须同模块。
 */
internal fun spellingChunks(term: String): List<String> {
    val letters = term.lowercase().filter { it.isLetter() }
    if (letters.length <= SINGLE_LETTER_MAX) return letters.map { it.toString() }
    val head = letters.take(3)
    val rest = letters.drop(3)
    val tail = ArrayDeque<String>()
    var end = rest.length
    while (end > 0) {
        val start = maxOf(0, end - 2)
        tail.addFirst(rest.substring(start, end))
        end = start
    }
    return listOf(head) + tail
}
