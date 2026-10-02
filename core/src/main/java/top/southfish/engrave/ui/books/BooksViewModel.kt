package top.southfish.engrave.ui.books

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.backup.BackupManager
import top.southfish.engrave.domain.model.WordBook
import top.southfish.engrave.domain.repository.SettingsRepository
import top.southfish.engrave.domain.repository.WordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BooksUiState(
    val books: List<WordBook> = emptyList(),
    /** 每本词书的估算占用字节数。 */
    val sizes: Map<Long, Long> = emptyMap(),
    /** 启用的词书 id（多选）；空集合 = 全部词书。 */
    val activeBookIds: Set<Long> = emptySet(),
    val busy: Boolean = false,
    val message: String? = null,
    val backups: List<BackupManager.BackupFile> = emptyList()
)

class BooksViewModel(
    private val wordRepository: WordRepository,
    private val settingsRepository: SettingsRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    private val status = MutableStateFlow(false to (null as String?))
    private val backups = MutableStateFlow<List<BackupManager.BackupFile>>(emptyList())

    val uiState: StateFlow<BooksUiState> = combine(
        wordRepository.observeBooks(),
        wordRepository.observeBookSizes(),
        settingsRepository.queueConfig,
        status,
        backups
    ) { books, sizes, config, (busy, message), files ->
        BooksUiState(
            books = books,
            sizes = sizes,
            activeBookIds = config.activeBookIds,
            busy = busy,
            message = message,
            backups = files
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BooksUiState())

    init {
        refreshBackups()
    }

    /** sourceKey 以 wordbooks/ 开头的是随包内置的默认词书：不能删，只能导出。 */
    fun isBuiltin(book: WordBook): Boolean =
        book.sourceKey != null && book.sourceKey.startsWith("wordbooks/")

    /**
     * 勾选 / 取消勾选一本词书（可多选）：
     * - 当前是「全部词书」（空集合）时点某本 → 只背这本；
     * - 点已选中的书 → 取消它；取消到一本不剩 → 自然回到「全部词书」。
     */
    fun toggleBook(bookId: Long) {
        val current = uiState.value.activeBookIds
        val next = if (current.isEmpty()) {
            setOf(bookId)
        } else if (bookId in current) {
            current - bookId
        } else {
            current + bookId
        }
        viewModelScope.launch { settingsRepository.setActiveBooks(next) }
    }

    /** 全部词书一起背（启用集合置空）。 */
    fun selectAllBooks() {
        viewModelScope.launch { settingsRepository.setActiveBooks(emptySet()) }
    }

    fun exportBook(bookId: Long) {
        viewModelScope.launch {
            status.value = true to null
            val message = runCatching { "已导出 Download/vocab/${backupManager.exportBookToDownloads(bookId)}" }
                .recover { it.message ?: "导出失败" }
                .getOrDefault("导出失败")
            status.value = false to message
            refreshBackups()
        }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            status.value = true to null
            val message = runCatching { "已导入「${backupManager.importBook(uri)}」" }
                .recover { it.message ?: "导入失败" }
                .getOrDefault("导入失败")
            status.value = false to message
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            runCatching {
                wordRepository.deleteBook(bookId)
                if (bookId in uiState.value.activeBookIds) {
                    settingsRepository.setActiveBooks(uiState.value.activeBookIds - bookId)
                }
            }
        }
    }

    fun refreshBackups() {
        viewModelScope.launch {
            backups.value = runCatching { backupManager.listBackups() }.getOrDefault(emptyList())
        }
    }

    fun consumeMessage() {
        status.value = status.value.first to null
    }
}
