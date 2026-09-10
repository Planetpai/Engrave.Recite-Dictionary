package top.southfish.engrave.data.mapper

import top.southfish.engrave.data.local.StudyItemRow
import top.southfish.engrave.data.local.entity.ReviewLogEntity
import top.southfish.engrave.data.local.entity.ReviewStateEntity
import top.southfish.engrave.data.local.entity.WordBookEntity
import top.southfish.engrave.data.local.entity.WordEntity
import top.southfish.engrave.domain.model.Grade
import top.southfish.engrave.domain.model.LearningStage
import top.southfish.engrave.domain.model.ReviewLog
import top.southfish.engrave.domain.model.ReviewState
import top.southfish.engrave.domain.model.StudyItem
import top.southfish.engrave.domain.model.Word
import top.southfish.engrave.domain.model.WordBook
import top.southfish.engrave.domain.srs.ReviewOutcome

// 枚举以名字存储。数据可能是旧版本写的或被人手改过，所以解析失败要退回安全值
// 而不是抛异常——一条脏数据不该让整个词表打不开。
private fun String.toStage(): LearningStage =
    runCatching { LearningStage.valueOf(this) }.getOrDefault(LearningStage.NEW)

private fun String.toGrade(): Grade =
    runCatching { Grade.valueOf(this) }.getOrDefault(Grade.GOOD)

fun WordEntity.toDomain(): Word = Word(
    id = id,
    bookId = bookId,
    term = term,
    meaning = meaning,
    phonetic = phonetic,
    example = example,
    exampleTranslation = exampleTranslation,
    position = position
)

fun WordBookEntity.toDomain(): WordBook = WordBook(
    id = id,
    name = name,
    description = description,
    sourceKey = sourceKey,
    wordCount = wordCount,
    createdAt = createdAt
)

fun ReviewStateEntity.toDomain(): ReviewState = ReviewState(
    wordId = wordId,
    stage = stage.toStage(),
    stepIndex = stepIndex,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    lapses = lapses,
    dueAt = dueAt,
    lastReviewedAt = lastReviewedAt
)

fun ReviewState.toEntity(): ReviewStateEntity = ReviewStateEntity(
    wordId = wordId,
    stage = stage.name,
    stepIndex = stepIndex,
    intervalDays = intervalDays,
    easeFactor = easeFactor,
    repetitions = repetitions,
    lapses = lapses,
    dueAt = dueAt,
    lastReviewedAt = lastReviewedAt
)

fun ReviewLogEntity.toDomain(): ReviewLog = ReviewLog(
    id = id,
    wordId = wordId,
    grade = grade.toGrade(),
    reviewedAt = reviewedAt,
    stageBefore = stageBefore.toStage(),
    stageAfter = stageAfter.toStage(),
    intervalBeforeDays = intervalBeforeDays,
    intervalAfterDays = intervalAfterDays,
    elapsedMillis = elapsedMillis
)

fun StudyItemRow.toDomain(): StudyItem = StudyItem(
    word = Word(
        id = state.wordId,
        bookId = bookId,
        term = term,
        meaning = meaning,
        phonetic = phonetic,
        example = example,
        exampleTranslation = exampleTranslation,
        position = position
    ),
    state = state.toDomain()
)

fun ReviewLog.toEntity(reviewDay: Long): ReviewLogEntity = ReviewLogEntity(
    id = id,
    wordId = wordId,
    grade = grade.name,
    reviewedAt = reviewedAt,
    reviewDay = reviewDay,
    stageBefore = stageBefore.name,
    stageAfter = stageAfter.name,
    intervalBeforeDays = intervalBeforeDays,
    intervalAfterDays = intervalAfterDays,
    elapsedMillis = elapsedMillis
)

/** 仅供调试/日志使用，把一次调度结果描述成人能读的一句话。 */
fun ReviewOutcome.describe(): String = when {
    graduated -> "毕业，%.0f 天后再见".format(scheduledInDays)
    scheduledInDays < 1.0 -> "%.0f 分钟后再见".format(scheduledInDays * 1440)
    else -> "%.1f 天后再见".format(scheduledInDays)
}
