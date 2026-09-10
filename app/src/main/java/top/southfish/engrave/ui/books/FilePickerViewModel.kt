package top.southfish.engrave.ui.books

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件选择器的导入用途：词书管理导入单本词书；数据管理恢复整包备份。 */
enum class PickerMode { BOOK, BACKUP }

data class PickerEntry(
    val file: File,
    val isDir: Boolean,
    /** 副信息行：文件显示「大小 · 修改日期」，文件夹显示「文件夹 · N 项」。 */
    val detail: String
)

data class FilePickerUiState(
    val dir: File? = null,
    /** 当前根目录：有权限是存储根目录（可浏览全部共享存储），没权限是应用专属目录（免授权可读） */
    val rootDir: File? = null,
    val entries: List<PickerEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val imported: Boolean = false,
    val hasPermission: Boolean = false,
    /** 当前目录无法读取（listFiles 返回 null，如系统受限目录）。 */
    val denied: Boolean = false,
    /** 备份模式下待确认的文件（全量恢复是破坏性操作，必须二次确认）。 */
    val pendingConfirmFile: File? = null
)

/**
 * 内置轻量文件选择器（手表没有 SAF 文件选择器）：
 * - 有存储权限：根目录是内部存储根（/storage/emulated/0），可逐级浏览任意
 *   文件夹（Download、Documents、词典/数据目录…），文件夹常驻列表顶部；
 * * 没权限：根目录是应用专属外部目录 files/books/，把 JSON 用 adb/电脑
 *   推到 Android/data/<包名>/files/books/ 即可免授权导入。
 *
 * [mode] = [PickerMode.BOOK]：点 .json 直接当词书导入（幂等），非 json 只提示；
 * [mode] = [PickerMode.BACKUP]：点 .json 先校验是整包备份，弹确认后才
 * 执行破坏性全量恢复（避免误用词书 JSON 清空全部数据）。
 */
class FilePickerViewModel(
    private val backupManager: BackupManager,
    private val appContext: Context,
    val mode: PickerMode = PickerMode.BOOK
) : ViewModel() {

    private val state = MutableStateFlow(FilePickerUiState())
    val uiState: StateFlow<FilePickerUiState> = state

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        refreshPermission()
    }

    /** 当前权限对应的根目录：有权限从存储根开始浏览任意文件夹，没权限用免授权的应用目录。 */
    private fun rootFor(granted: Boolean): File =
        if (granted) {
            Environment.getExternalStorageDirectory()
        } else {
            // getExternalFilesDir 无需任何运行时权限即可读写
            (appContext.getExternalFilesDir("books") ?: File(appContext.filesDir, "books"))
                .apply { runCatching { mkdirs() } }
        }

    /**
     * 重新检查存储权限。从系统设置/授权弹窗回来后会在 ON_RESUME 调用：
     * 权限状态变化时切换根目录并重新列目录。
     *
     * targetSdk 29 传统存储下，授予 READ_EXTERNAL_STORAGE 即可读 Download；
     * 若哪天获得了「所有文件访问」（MANAGE）也算通过。
     */
    fun refreshPermission() {
        val granted = Environment.isExternalStorageManager() ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        val root = rootFor(granted)
        val current = state.value
        // 权限没变且当前目录还在根目录以内，就保持原位刷新；否则回到根目录
        val keepDir = current.dir != null &&
            current.hasPermission == granted &&
            current.dir!!.startsWith(root)
        val target = if (keepDir) current.dir!! else root
        state.value = current.copy(hasPermission = granted, rootDir = root, dir = target)
        openDir(target)
    }

    fun openDir(dir: File) {
        viewModelScope.launch {
            var denied = false
            val entries = withContext(Dispatchers.IO) {
                runCatching {
                    // 文件夹全显示；文件也全显示（用户要求能浏览到词典/数据文件），
                    // 非 json 在点击时提示不支持，json 才可导入
                    val listed = dir.listFiles()
                    if (listed == null) {
                        denied = true
                        emptyList<PickerEntry>()
                    } else {
                        listed
                            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                            .map { f ->
                                PickerEntry(
                                    file = f,
                                    isDir = f.isDirectory,
                                    detail = if (f.isDirectory) {
                                        val count = f.listFiles()?.size ?: 0
                                        "文件夹 · $count 项"
                                    } else if (f.extension.equals("json", true)) {
                                        "${formatSize(f.length())} · ${dateFormat.format(Date(f.lastModified()))}"
                                    } else {
                                        "${formatSize(f.length())} · 仅支持导入 .json"
                                    }
                                )
                            }
                    }
                }.getOrElse {
                    denied = true
                    emptyList<PickerEntry>()
                }
            }
            state.value = state.value.copy(dir = dir, entries = entries, denied = denied)
        }
    }

    /** 条目点击：文件夹进入；json 才能导入（其他文件提示）；备份模式校验后弹确认。 */
    fun onEntryTap(entry: PickerEntry) {
        if (entry.isDir) {
            openDir(entry.file)
            return
        }
        if (!entry.file.extension.equals("json", true)) {
            state.value = state.value.copy(message = "只支持导入 .json 文件")
            return
        }
        when (mode) {
            PickerMode.BOOK -> importBook(entry.file)
            PickerMode.BACKUP -> requestBackupImport(entry.file)
        }
    }

    private fun importBook(file: File) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, message = null)
            val message = runCatching { "已导入「${backupManager.importBook(Uri.fromFile(file))}」" }
                .recover { it.message ?: "导入失败" }
                .getOrDefault("导入失败")
            state.value = state.value.copy(
                busy = false,
                message = message,
                imported = message.startsWith("已导入")
            )
        }
    }

    /** 备份模式：先读出文件确认是整包备份（词书 JSON 顶层是 words，不是备份），再弹确认。 */
    private fun requestBackupImport(file: File) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, message = null)
            val result = runCatching {
                val text = withContext(Dispatchers.IO) { file.readText() }
                val root = JSONObject(text)
                val looksLikeBackup = root.has("format") || root.has("books") || root.has("states")
                if (!looksLikeBackup) {
                    false to "这不是数据备份文件\n词书请在「词书管理」里导入"
                } else {
                    true to ""
                }
            }.getOrElse { false to (it.message ?: "无法读取该文件") }
            state.value = state.value.copy(
                busy = false,
                pendingConfirmFile = if (result.first) file else null,
                message = if (result.first) null else result.second
            )
        }
    }

    /** 确认框：执行破坏性全量恢复。 */
    fun confirmBackupImport() {
        val file = state.value.pendingConfirmFile ?: return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, pendingConfirmFile = null, message = null)
            val message = runCatching {
                val count = backupManager.importAll(Uri.fromFile(file))
                "数据已恢复（$count 本词书）"
            }.recover { it.message ?: "恢复失败" }.getOrDefault("恢复失败")
            state.value = state.value.copy(
                busy = false,
                message = message,
                imported = message.startsWith("数据已恢复")
            )
        }
    }

    fun cancelBackupImport() {
        state.value = state.value.copy(pendingConfirmFile = null)
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) "${bytes / 1024 / 1024} MB" else "${(bytes + 1023) / 1024} KB"
}
