package top.southfish.engrave.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.local.DayIndex
import top.southfish.engrave.data.session.StudySessionStore
import top.southfish.engrave.domain.model.StudyQueueConfig
import top.southfish.engrave.domain.model.StudyStats
import top.southfish.engrave.domain.model.WordBook
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.StudyRepository
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val isLoading: Boolean = true,
    val stats: StudyStats = StudyStats(),
    val books: List<WordBook> = emptyList(),
    val activeBookName: String? = null,
    val dailyNewLimit: Int = StudyQueueConfig.DEFAULT.dailyNewLimit,
    /** 有未走完的学习会话快照（中途退出过），按钮必须保持可点以便续背。 */
    val resumable: Boolean = false
) {
    /** 今天还能学几个新词。 */
    val newRemainingToday: Int
        get() = minOf(stats.newRemaining, dailyNewLimit)

    /** 这次点「开始学习」大概会拿到多少张卡。 */
    val sessionSize: Int
        get() = minOf(stats.dueNow + newRemainingToday, 20)

    val hasAnythingToStudy: Boolean
        get() = stats.dueNow > 0 || newRemainingToday > 0

    /** 按钮永远可点：有额度显示「开始学习 N」，没有但有断点显示「继续背单词」。 */
    val buttonEnabled: Boolean
        get() = !isLoading

    val buttonLabel: String
        get() = when {
            sessionSize > 0 -> "开始学习 $sessionSize"
            resumable -> "继续背单词"
            else -> "继续学习"
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    studyRepository: StudyRepository,
    settingsRepository: SettingsRepository,
    wordRepository: WordRepository,
    private val sessionStore: StudySessionStore
) : ViewModel() {

    /**
     * 设置里的 activeBookIds 决定统计范围，所以要先拿到设置再 flatMapLatest
     * 切换到对应的统计流。用户改了词书，首页数字会立刻跟着变。
     */
    val uiState: StateFlow<HomeUiState> = settingsRepository.queueConfig
        .flatMapLatest { config ->
            combine(
                studyRepository.observeStats(config.activeBookIds),
                wordRepository.observeBooks(),
                kotlinx.coroutines.flow.flow {
                    emit(sessionStore.load(DayIndex.today())?.hasContent == true)
                }
            ) { stats, books, resumable ->
                HomeUiState(
                    isLoading = false,
                    stats = stats,
                    books = books,
                    activeBookName = when (config.activeBookIds.size) {
                        // 空集合 = 全部词书；只选一本显示书名；多本显示数量
                        0 -> null
                        1 -> books.firstOrNull { it.id == config.activeBookIds.first() }?.name
                        else -> "${config.activeBookIds.size} 本词书"
                    },
                    dailyNewLimit = config.dailyNewLimit,
                    resumable = resumable
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            // 手表上应用经常被切到后台，停 5 秒后停止上游收集省电
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState()
        )
}
