package com.example.vocabwatch.domain.srs

import com.example.vocabwatch.domain.model.Grade
import com.example.vocabwatch.domain.model.ReviewState

/**
 * 时间来源。抽出来是为了让调度算法可以在单元测试里用固定时间，
 * 不依赖 System.currentTimeMillis()。
 */
fun interface TimeProvider {
    fun now(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}

/**
 * 一次评分后的调度结果。
 *
 * @param newState 更新后的调度状态，直接落库
 * @param scheduledInDays 距离下次到期还有多少天；学习阶段是分钟换算的小数，
 *   UI 可以据此显示「10 分钟后」还是「3 天后」
 * @param graduated 本次是否从学习阶段毕业进入长期间隔复习
 */
data class ReviewOutcome(
    val newState: ReviewState,
    val scheduledInDays: Double,
    val graduated: Boolean = false
)

/**
 * 间隔重复调度器。领域层只依赖这个接口，具体算法（SM-2、FSRS……）
 * 可以自由替换而不影响仓库、用例和 UI。
 */
interface Scheduler {
    /** 算法名，用于调试和统计 */
    val name: String

    /**
     * 根据当前状态和本次评分算出下一次复习时间。
     * 必须是纯函数：不读写数据库、不取系统时间，now 由调用方传入。
     */
    fun schedule(state: ReviewState, grade: Grade, now: Long): ReviewOutcome
}
