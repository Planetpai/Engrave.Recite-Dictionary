package top.southfish.engrave.di

import android.content.Context
import top.southfish.engrave.data.backup.BackupManager
import top.southfish.engrave.data.dictionary.DictRepository
import top.southfish.engrave.data.local.AppDatabase
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.data.local.dao.SearchHistoryDao
import top.southfish.engrave.data.repository.SettingsRepositoryImpl
import top.southfish.engrave.data.repository.StudyRepositoryImpl
import top.southfish.engrave.data.repository.WordRepositoryImpl
import top.southfish.engrave.data.seed.SeedDataProvider
import top.southfish.engrave.data.session.StudySessionStore
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.StudyRepository
import top.southfish.engrave.domain.repository.WordRepository
import top.southfish.engrave.domain.srs.Scheduler
import top.southfish.engrave.domain.srs.Sm2Scheduler
import top.southfish.engrave.domain.srs.SystemTimeProvider
import top.southfish.engrave.domain.srs.TimeProvider
import top.southfish.engrave.domain.usecase.GetStudySessionUseCase
import top.southfish.engrave.domain.usecase.RestoreSessionUseCase
import top.southfish.engrave.domain.usecase.SubmitReviewUseCase
import top.southfish.engrave.domain.usecase.UndoReviewUseCase

/**
 * 手写的依赖容器。
 *
 * 没有用 Hilt：当前工具链是 AGP 8.2 / Kotlin 1.9.20，再叠一层注解处理器
 * 只会增加版本匹配的风险，而这个规模的依赖图手写三十行就够清楚。
 * 以后依赖变复杂了，这里的每个 by lazy 都能一对一换成 @Provides。
 *
 * 全部用 lazy 是为了让 Application.onCreate 保持轻量——手表上冷启动时间
 * 很敏感，数据库不该在没进任何页面时就被打开。
 */
class AppContainer(private val context: Context) {

    val appContext: Context = context.applicationContext

    val timeProvider: TimeProvider = SystemTimeProvider

    /** 换调度算法（比如以后上 FSRS）只需要改这一行。 */
    val scheduler: Scheduler by lazy { Sm2Scheduler() }

    val database: AppDatabase by lazy { AppDatabase.create(context) }

    val wordRepository: WordRepository by lazy {
        WordRepositoryImpl(
            database = database,
            wordBookDao = database.wordBookDao(),
            wordDao = database.wordDao(),
            reviewStateDao = database.reviewStateDao()
        )
    }

    val studyRepository: StudyRepository by lazy {
        StudyRepositoryImpl(
            database = database,
            reviewStateDao = database.reviewStateDao(),
            reviewLogDao = database.reviewLogDao(),
            timeProvider = timeProvider
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(context)
    }

    /** ECDICT 离线词典，搜索页和详情页用。 */
    val dictRepository: DictRepository by lazy { DictRepository(appContext) }

    /** 系统 TTS 朗读，详情页喇叭按钮用。 */
    val ttsSpeaker: top.southfish.engrave.data.tts.TtsSpeaker by lazy {
        top.southfish.engrave.data.tts.TtsSpeaker(appContext)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(appContext, database, wordRepository, settingsRepository, sessionStore, seedDataProvider)
    }

    val searchDao: SearchHistoryDao by lazy { database.searchHistoryDao() }

    val mistakeDao: MistakeDao by lazy { database.mistakeDao() }

    val getStudySession: GetStudySessionUseCase by lazy {
        GetStudySessionUseCase(studyRepository, timeProvider)
    }

    /** 学习会话断点续传。 */
    val sessionStore: StudySessionStore by lazy { StudySessionStore(appContext.filesDir) }

    val restoreSession: RestoreSessionUseCase by lazy { RestoreSessionUseCase(studyRepository) }


    val submitReview: SubmitReviewUseCase by lazy {
        SubmitReviewUseCase(studyRepository, scheduler, timeProvider)
    }

    val undoReview: UndoReviewUseCase by lazy {
        UndoReviewUseCase(studyRepository)
    }

    val seedDataProvider: SeedDataProvider by lazy {
        SeedDataProvider(context, wordRepository, settingsRepository)
    }
}
