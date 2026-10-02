package top.southfish.engrave.ui.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.backup.BackupManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DataUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val backups: List<BackupManager.BackupFile> = emptyList()
)

/** 数据管理：导出 / 破坏性恢复 / 重置全部数据。 */
class DataViewModel(private val backupManager: BackupManager) : ViewModel() {

    private val status = MutableStateFlow(false to (null as String?))
    private val backups = MutableStateFlow<List<BackupManager.BackupFile>>(emptyList())

    val uiState: StateFlow<DataUiState> = combine(status, backups) { (busy, message), files ->
        DataUiState(busy = busy, message = message, backups = files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataUiState())

    init {
        refreshBackups()
    }

    fun exportAll() {
        viewModelScope.launch {
            status.value = true to null
            val message = runCatching { "已导出 Download/vocab/${backupManager.exportAllToDownloads()}" }
                .recover { it.message ?: "导出失败" }
                .getOrDefault("导出失败")
            status.value = false to message
            refreshBackups()
        }
    }

    /** 恢复是破坏性的（先清空再灌入），调用前必须弹确认框。 */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            status.value = true to null
            val message = runCatching { "已恢复 ${backupManager.importAll(uri)} 本词书" }
                .recover { it.message ?: "导入失败" }
                .getOrDefault("导入失败")
            status.value = false to message
            refreshBackups()
        }
    }

    /** 重置全部数据并恢复初始状态。 */
    fun resetAll() {
        viewModelScope.launch {
            status.value = true to null
            runCatching { backupManager.resetAllData() }
            status.value = false to "已重置全部数据"
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
