package com.example.vocabwatch.domain.usecase

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.ReviewState
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.srs.ReviewOutcome
import com.example.vocabwatch.domain.srs.Scheduler
import com.example.vocabwatch.domain.srs.TimeProvider

/**
 * 一次学习会话的原料。
 *
 * 有意分成两个列表而不是混排：产品流程是「先过新词、再复习到期词」，
 * 新词内部还要先浏览选词再正式测试，混在一起就没法表达阶段了。
 * 每日额度在这里扣掉，调用方拿到的就是今天真正允许学的量。
 */
data class SessionPlan(
    val newItems: List<StudyItem>,
    val dueItems: List<StudyItem>,
    /**
     * 浏览选词的目标：要收集到这么多「不认识」的词才进入背单词。
     * newItems 是预取的批次（比目标多，用户左滑跳过的不占目标）。
     */
    val selectionTarget: Int = newItems.size
) {
    val isEmpty: Boolean get() = newItems.isEmpty() && dueItems.isEmpty()
}

class GetStudySessionUseCase(
    private val studyRepository: StudyRepository,
    private val timeProvider: TimeProvider
) {
    /**
     * [newLimitOverride] 非 null 时忽略每日新词上限，直接取这么多新词
     * （「继续再学」按钮用：学新单词，绕过当日额度）。
     */
    suspend operator fun invoke(
        config: StudyQueueConfig,
        newLimitOverride: Int? = null
    ): SessionPlan {
        val bookId = config.activeBookId
        val now = timeProvider.now()

        val newLearnedToday = studyRepository.countNewLearnedToday(bookId)

        val newBudget = newLimitOverride ?: maxOf(0, config.dailyNewLimit - newLearnedToday)

        // 浏览批次多预取一些：用户左滑「已学过」的不占目标，
        // 直到攒够 newBudget 个「不认识」的词才进背单词
        return SessionPlan(
            newItems = if (newBudget == 0) {
                emptyList()
            } else {
                studyRepository.getNewItems(bookId, newBudget + BROWSE_BUFFER)
            },
            dueItems = studyRepository.getDueItems(bookId, REVIEW_UNLIMITED, now),
            selectionTarget = newBudget
        )
    }

    companion object {
        /** 复习队列的单次上限，实际上到期词远到不了这个数。 */
        const val REVIEW_UNLIMITED = 500

        /** 浏览批次在目标之外多预取的词数，覆盖「已学过」跳过的部分。 */
        const val BROWSE_BUFFER = 30
    }

}

/**
 * 恢复中断的会话：按快照里的词 id 批量取回带最新调度状态的卡片。
 */
class RestoreSessionUseCase(private val studyRepository: StudyRepository) {
    suspend operator fun invoke(wordIds: List<Long>): Map<Long, StudyItem> =
        studyRepository.getItemsByIds(wordIds).associateBy { it.wordId }
}

/**
 * 提交一次评分：跑调度算法，然后把新状态和日志一起落库。
 *
 * 调度本身是纯函数（在 Scheduler 里），这里只负责编排。
 */
class SubmitReviewUseCase(
    private val studyRepository: StudyRepository,
    private val scheduler: Scheduler,
    private val timeProvider: TimeProvider
) {
    suspend operator fun invoke(
        item: StudyItem,
        grade: Grade,
        elapsedMillis: Long = 0
    ): ReviewOutcome {
        val outcome = scheduler.schedule(item.state, grade, timeProvider.now())
        studyRepository.persistReview(item, grade, outcome, elapsedMillis)
        return outcome
    }
}

/**
 * 撤销一次评分：状态回到评分前，并删掉那条复习日志。
 *
 * [restored] 为 null 表示被撤销的那次操作压根没落库（例如浏览阶段的「不认识」
 * 只是把词加进待背列表），这种情况直接返回，否则会把别的日志误删。
 */
class UndoReviewUseCase(private val studyRepository: StudyRepository) {
    suspend operator fun invoke(wordId: Long, restored: ReviewState?) {
        if (restored == null) return
        studyRepository.restoreStateAndDropLastLog(wordId, restored)
    }
}
