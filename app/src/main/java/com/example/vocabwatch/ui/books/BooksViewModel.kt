package com.example.vocabwatch.ui.books

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.backup.BackupManager
import com.example.vocabwatch.domain.model.WordBook
import com.example.vocabwatch.domain.repository.SettingsRepository
import com.example.vocabwatch.domain.repository.WordRepository
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
    val activeBookId: Long? = null,
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
            activeBookId = config.activeBookId,
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

    fun selectBook(bookId: Long) {
        viewModelScope.launch { settingsRepository.setActiveBook(bookId) }
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
                if (uiState.value.activeBookId == bookId) {
                    settingsRepository.setActiveBook(null)
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
