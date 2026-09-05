package com.example.vocabwatch.domain.usecase

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.StudyItem
import com.example.vocabwatch.domain.model.StudyQueueConfig
import com.example.vocabwatch.domain.repository.StudyRepository
import com.example.vocabwatch.domain.srs.ReviewOutcome
import com.example.vocabwatch.domain.srs.Scheduler
import com.example.vocabwatch.domain.srs.TimeProvider
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 组装一次学习会话的卡片队列。
 *
 * 返回的是快照而不是 Flow：会话进行中队列不能变，否则刚答对的卡片会因为
 * dueAt 变化而从列表里消失，导致 UI 错乱。
 *
 * 取词策略：先拿逾期最久的到期卡，再按 newCardShare 的比例插入新词，
 * 两者都受每日上限约束，最后截断到 sessionSize。
 */
class GetStudySessionUseCase(
    private val studyRepository: StudyRepository,
    private val timeProvider: TimeProvider
) {
    suspend operator fun invoke(config: StudyQueueConfig): List<StudyItem> {
        val bookId = config.activeBookId
        val now = timeProvider.now()
        val sessionSize = max(1, config.sessionSize)

        val newLearnedToday = studyRepository.countNewLearnedToday(bookId)
        val reviewedToday = studyRepository.countReviewsToday(bookId)

        val newBudget = max(0, minOf(config.dailyNewLimit - newLearnedToday, sessionSize))
        val reviewBudget = max(
            0,
            minOf(config.dailyReviewLimit - reviewedToday, sessionSize)
        )

        val dueItems = if (reviewBudget == 0) {
            emptyList()
        } else {
            studyRepository.getDueItems(bookId, reviewBudget, now)
        }

        // 到期卡已经占满会话时就不再塞新词，避免一次会话过长
        val roomLeft = sessionSize - dueItems.size
        val newLimit = if (roomLeft <= 0) {
            0
        } else {
            val byShare = (sessionSize * config.newCardShare).roundToInt()
            minOf(newBudget, roomLeft, max(0, byShare))
        }

        val newItems = if (newLimit == 0) {
            emptyList()
        } else {
            studyRepository.getNewItems(bookId, newLimit)
        }

        return interleave(dueItems, newItems).take(sessionSize)
    }

    /**
     * 到期卡和新词交替排列，而不是先把新词全堆在前面。
     * 连续看一堆生词体验很差，穿插着来记忆负担更均匀。
     */
    private fun interleave(due: List<StudyItem>, new: List<StudyItem>): List<StudyItem> {
        if (due.isEmpty()) return new
        if (new.isEmpty()) return due

        val result = ArrayList<StudyItem>(due.size + new.size)
        // 每隔 gap 张到期卡插一张新词
        val gap = max(1, (due.size.toDouble() / new.size).roundToInt())
        var dueIndex = 0
        var newIndex = 0
        while (dueIndex < due.size || newIndex < new.size) {
            if (newIndex < new.size && dueIndex % (gap + 1) == gap) {
                result += new[newIndex++]
            } else if (dueIndex < due.size) {
                result += due[dueIndex++]
            } else {
                result += new[newIndex++]
            }
        }
        return result
    }
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
