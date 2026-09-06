package com.example.vocabwatch.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vocabwatch.data.local.dao.MistakeDao
import com.example.vocabwatch.data.local.entity.MistakeEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MistakesViewModel(private val mistakeDao: MistakeDao) : ViewModel() {

    val mistakes: StateFlow<List<MistakeEntity>> = mistakeDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(term: String) {
        viewModelScope.launch { mistakeDao.delete(term) }
    }
}
