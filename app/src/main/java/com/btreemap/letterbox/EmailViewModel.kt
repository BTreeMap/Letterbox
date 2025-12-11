package com.btreemap.letterbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EmailViewModel(
    private val repository: InMemoryHistoryRepository
) : ViewModel() {

    val history: StateFlow<List<HistoryEntry>> = repository.items

    fun ingestSample(displayName: String = "Sample EML") {
        viewModelScope.launch {
            repository.ingest(
                bytes = "Subject: $displayName\n\nHello from Letterbox.".toByteArray(),
                displayName = displayName,
                originalUri = null
            )
        }
    }
}

class EmailViewModelFactory(
    private val repository: InMemoryHistoryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EmailViewModel::class.java)) {
            return EmailViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
