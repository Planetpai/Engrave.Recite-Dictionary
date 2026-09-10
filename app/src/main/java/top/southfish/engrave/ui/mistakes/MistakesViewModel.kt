package top.southfish.engrave.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.southfish.engrave.data.local.dao.MistakeDao
import top.southfish.engrave.data.local.entity.MistakeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 删除模式的阶段：未进入 → 标记中 → 待确认。 */
enum class DeleteStage { NONE, MARKING, CONFIRM }

data class MistakesUiState(
    val mistakes: List<MistakeEntity> = emptyList(),
    /** 是否处于删除模式（普通模式左滑无效，防误触）。 */
    val deleteStage: DeleteStage = DeleteStage.NONE,
    /** 删除模式下被标记的词。 */
    val marked: Set<String> = emptySet()
)

class MistakesViewModel(private val mistakeDao: MistakeDao) : ViewModel() {

    private val deleteState = MutableStateFlow(DeleteStage.NONE to emptySet<String>())

    val uiState: StateFlow<MistakesUiState> = combine(
        mistakeDao.observeAll(),
        deleteState
    ) { mistakes, (stage, marked) ->
        MistakesUiState(mistakes = mistakes, deleteStage = stage, marked = marked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MistakesUiState())

    /** 进入 / 退出删除模式（退出时清掉标记）。 */
    fun toggleDeleteMode() {
        val (stage, marked) = deleteState.value
        deleteState.value = when (stage) {
            DeleteStage.NONE -> DeleteStage.MARKING to emptySet()
            else -> DeleteStage.NONE to emptySet()
        }
    }

    /** 确认页。 */
    fun showConfirm() {
        val (stage, marked) = deleteState.value
        if (stage == DeleteStage.MARKING && marked.isNotEmpty()) {
            deleteState.value = DeleteStage.CONFIRM to marked
        }
    }

    fun cancelConfirm() {
        val (stage, marked) = deleteState.value
        if (stage == DeleteStage.CONFIRM) deleteState.value = DeleteStage.MARKING to marked
    }

    /** 删除模式下：左滑一次标记（变红），再滑一次取消标记。 */
    fun toggleMark(term: String) {
        val (stage, marked) = deleteState.value
        if (stage != DeleteStage.MARKING) return
        deleteState.value = stage to if (term in marked) marked - term else marked + term
    }

    /** 真正执行删除。 */
    fun deleteMarked() {
        val (stage, marked) = deleteState.value
        if (stage != DeleteStage.CONFIRM) return
        viewModelScope.launch {
            marked.forEach { mistakeDao.delete(it) }
            deleteState.value = DeleteStage.NONE to emptySet()
        }
    }
}
