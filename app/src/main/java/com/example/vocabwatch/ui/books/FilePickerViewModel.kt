package com.example.vocabwatch.ui.books

import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PickerEntry(val file: File, val isDir: Boolean)

data class FilePickerUiState(
    val dir: File? = null,
    val entries: List<PickerEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val imported: Boolean = false
)

/**
 * 内置轻量文件选择器（手表没有 SAF 文件选择器）：从 Download 出发浏览
 * 目录、点 .json 词书文件直接导入。需要「所有文件访问」权限，入口页会引导。
 */
class FilePickerViewModel(private val backupManager: BackupManager) : ViewModel() {

    private val state = MutableStateFlow(FilePickerUiState())
    val uiState: StateFlow<FilePickerUiState> = state

    init {
        openDir(DEFAULT_DIR)
    }

    fun openDir(dir: File) {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    dir.listFiles()
                        .orEmpty()
                        .filter { it.isDirectory || it.extension.equals("json", true) }
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                        .map { PickerEntry(it, it.isDirectory) }
                }.getOrDefault(emptyList())
            }
            state.value = state.value.copy(dir = dir, entries = entries)
        }
    }

    fun import(file: File) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, message = null)
            val message = runCatching { "已导入「${backupManager.importBook(Uri.fromFile(file))}」" }
                .recover { it.message ?: "导入失败" }
                .getOrDefault("导入失败")
            state.value = state.value.copy(busy = false, message = message, imported = message.startsWith("已导入"))
        }
    }

    companion object {
        val DEFAULT_DIR: File = File(Environment.getExternalStorageDirectory(), "Download")
    }
}
