package top.southfish.engrave.translate

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 翻译页状态机（对应用户确认的执行清单 §8，语音相关的 5/6 已作废）。 */
enum class TranslatePhase {
    /** 选定模型还没准备好：展示可选模型清单，等用户下载/导入。 */
    NEED_MODEL,

    /** 正在下载或导入模型。 */
    DOWNLOADING,

    /** 模型在加载（ONNX 建 session / ML Kit 下发语言模型）。 */
    LOADING,

    /** 就绪：可以输入并翻译。 */
    READY,

    /** 推理中。 */
    TRANSLATING,

    /** 出结果。 */
    DONE,

    /** 出错：[TranslateUiState.message] 里有原因。 */
    FAILED
}

data class TranslateUiState(
    val phase: TranslatePhase = TranslatePhase.NEED_MODEL,
    /** 当前选定的模型条目，决定引擎与档位。 */
    val selected: TranslateModelEntry = TranslateCatalog.onnxEnZhQuick,
    /** 已下载完成的 ONNX 条目 key 集合。 */
    val installed: Set<String> = emptySet(),
    /** 正在下载/导入的条目 key；无则 null。 */
    val busyKey: String? = null,
    /** 0f..1f；负值表示不确定进度。 */
    val progress: Float = -1f,
    val input: String = "",
    val output: String = "",
    /** 最近一次实际使用的方向与引擎，用于结果区灰字。 */
    val lastDirection: TranslateDirection? = null,
    val lastEngineName: String? = null,
    val message: String? = null
) {
    /** 当前输入会走的方向。 */
    val direction: TranslateDirection get() = TranslateCatalog.detectDirection(input)

    /** 选中条目是否已可用（ML Kit 恒可用，ONNX 看是否已下载）。 */
    val selectedReady: Boolean
        get() = selected.engine == TranslateEngineType.ML_KIT || installed.contains(selected.key)

    /** 当前输入方向下、选定档位的 ONNX 条目是否已下载。 */
    val neededEntryReady: Boolean
        get() = if (selected.engine == TranslateEngineType.ML_KIT) true
        else installed.contains(TranslateCatalog.onnx(direction, selected.tier!!).key)

    /** 可以点了。 */
    val canTranslate: Boolean
        get() = input.isNotBlank() &&
            phase != TranslatePhase.TRANSLATING &&
            phase != TranslatePhase.LOADING &&
            phase != TranslatePhase.DOWNLOADING &&
            neededEntryReady
}

/**
 * 翻译页 ViewModel。
 *
 * 生命周期纪律：引擎（ONNX session / ML Kit client）只在需要时创建，
 * 页面离开（[onCleared]）或换引擎时立刻 [TranslationEngine.close]。
 * 手表内存小，不允许把模型留在后台。
 */
class TranslateViewModel(private val appContext: Context) : ViewModel() {

    private val store = TranslateModelStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var engine: TranslationEngine? = null

    /** 当前引擎对应的 (条目 key, 方向)，不一致时重建。 */
    private var engineSignature: Pair<String, TranslateDirection>? = null

    private val _uiState = MutableStateFlow(
        TranslateUiState(
            selected = restoreSelected(),
            installed = store.installedKeys()
        ).let { it.copy(phase = if (it.selectedReady) TranslatePhase.READY else TranslatePhase.NEED_MODEL) }
    )
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    fun onInputChange(text: String) {
        _uiState.update {
            it.copy(input = text, message = null, phase = resetPhase(it))
        }
    }

    /** 切换选定的模型条目（引擎与档位都跟着变）。 */
    fun select(entry: TranslateModelEntry) {
        prefs.edit().putString(KEY_SELECTED, entry.key).apply()
        releaseEngine()
        _uiState.update {
            it.copy(
                selected = entry,
                message = null,
                output = "",
                phase = resetPhase(it.copy(selected = entry))
            )
        }
    }

    /** 从 GitHub Release 下载选中条目。 */
    fun download(entry: TranslateModelEntry) {
        if (_uiState.value.busyKey != null) return
        _uiState.update { it.copy(phase = TranslatePhase.DOWNLOADING, busyKey = entry.key, progress = -1f, message = null) }
        viewModelScope.launch {
            val result = store.download(entry) { p -> _uiState.update { it.copy(progress = p) } }
            finishInstall(result)
        }
    }

    /** 手动导入兜底：选一个本地 zip。 */
    fun importZip(entry: TranslateModelEntry, uri: Uri) {
        if (_uiState.value.busyKey != null) return
        _uiState.update { it.copy(phase = TranslatePhase.DOWNLOADING, busyKey = entry.key, progress = -1f, message = null) }
        viewModelScope.launch {
            val result = store.importFromZip(entry, uri) { p -> _uiState.update { it.copy(progress = p) } }
            finishInstall(result)
        }
    }

    fun delete(entry: TranslateModelEntry) {
        if (_uiState.value.busyKey != null) return
        releaseEngine()
        store.delete(entry)
        _uiState.update {
            val installed = store.installedKeys()
            it.copy(
                installed = installed,
                output = "",
                phase = resetPhase(it.copy(installed = installed))
            )
        }
    }

    fun translate() {
        val state = _uiState.value
        if (!state.canTranslate) return
        val direction = state.direction
        val entry = if (state.selected.engine == TranslateEngineType.ML_KIT) {
            TranslateCatalog.mlKit
        } else {
            TranslateCatalog.onnx(direction, state.selected.tier!!)
        }
        val text = state.input.trim()
        _uiState.update { it.copy(phase = TranslatePhase.LOADING, message = null) }
        viewModelScope.launch {
            try {
                val active = obtainEngine(entry, direction)
                _uiState.update { it.copy(phase = TranslatePhase.TRANSLATING) }
                val out = active.translate(text, direction)
                _uiState.update {
                    it.copy(
                        phase = TranslatePhase.DONE,
                        output = out,
                        lastDirection = direction,
                        lastEngineName = active.describe,
                        message = null
                    )
                }
            } catch (e: Exception) {
                // 引擎可能已半初始化，坏掉就丢掉，下次重新建
                releaseEngine()
                _uiState.update {
                    it.copy(
                        phase = TranslatePhase.FAILED,
                        output = "",
                        message = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    /** 页面销毁：立刻释放模型。 */
    override fun onCleared() {
        releaseEngine()
    }

    /** 页面不可见时也可主动调用（Composable 侧 onDispose）。 */
    fun releaseEngine() {
        engine?.let { runCatching { it.close() } }
        engine = null
        engineSignature = null
    }

    private suspend fun obtainEngine(entry: TranslateModelEntry, direction: TranslateDirection): TranslationEngine {
        val signature = entry.key to direction
        engine?.takeIf { engineSignature == signature }?.let { return it }
        releaseEngine()
        val created = createTranslationEngine(appContext, entry, direction)
        engine = created
        engineSignature = signature
        return created
    }

    private fun finishInstall(result: Result<Unit>) {
        val installed = store.installedKeys()
        _uiState.update { current ->
            result.fold(
                onSuccess = {
                    current.copy(
                        installed = installed,
                        busyKey = null,
                        progress = -1f,
                        message = null,
                        phase = resetPhase(current.copy(installed = installed))
                    )
                },
                onFailure = { e ->
                    current.copy(
                        installed = installed,
                        busyKey = null,
                        progress = -1f,
                        phase = TranslatePhase.FAILED,
                        message = "模型下载失败：" +
                            (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                    )
                }
            )
        }
    }

    /**
     * 回到「该处于哪一态」：能翻译就 READY，不能就 NEED_MODEL。
     * 注意 FAILED 不在此列——失败信息要留着给用户看（[TranslateUiState.message]）。
     */
    private fun resetPhase(state: TranslateUiState): TranslatePhase = when {
        state.phase == TranslatePhase.FAILED -> TranslatePhase.FAILED
        state.selectedReady -> TranslatePhase.READY
        else -> TranslatePhase.NEED_MODEL
    }

    private fun restoreSelected(): TranslateModelEntry {
        val key = prefs.getString(KEY_SELECTED, null)
        return TranslateCatalog.all.firstOrNull { it.key == key } ?: TranslateCatalog.onnxEnZhQuick
    }

    private companion object {
        const val PREFS_NAME = "translate"
        const val KEY_SELECTED = "selected_model"
    }
}
