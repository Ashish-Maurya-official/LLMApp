package com.example.llmapp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.database.MemoryDao
import com.example.llmapp.core.database.SemanticMemoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val memoryDao: MemoryDao) : ViewModel() {

    // Categorized memories map
    val categorizedMemories: StateFlow<Map<String, List<SemanticMemoryEntity>>> =
        memoryDao.getAllSemanticMemoriesFlow()
            .map { memories ->
                memories.groupBy { it.category.capitalize() }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    fun forgetMemory(id: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryDao.deleteSemanticMemory(id)
        }
    }

    fun updateMemory(id: Long, newContent: String, originalContent: String?) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryDao.updateSemanticMemoryUserEdit(
                id = id,
                newContent = newContent,
                originalContent = originalContent ?: newContent,
                ts = System.currentTimeMillis()
            )
        }
    }

    fun togglePin(id: Long, isPinned: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryDao.toggleSemanticMemoryPin(id, if (!isPinned) 1 else 0)
        }
    }
}
