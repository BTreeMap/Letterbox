package com.btreemap.letterbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.btreemap.letterbox.ffi.EmailHandle
import com.btreemap.letterbox.ffi.ParseException
import com.btreemap.letterbox.ffi.parseEml
import com.btreemap.letterbox.ui.EmailContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for the main screen.
 */
data class EmailUiState(
    val history: List<HistoryEntry> = emptyList(),
    val currentEmail: EmailContent? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class EmailViewModel(
    private val repository: InMemoryHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    init {
        // Observe history changes
        viewModelScope.launch {
            repository.items.collect { items ->
                _uiState.update { it.copy(history = items) }
            }
        }
    }

    /**
     * Ingest email from a URI (content:// or file://).
     */
    fun ingestFromUri(bytes: ByteArray, filename: String, uri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                // Parse the email to extract subject for display name
                val parsed = parseEmailBytes(bytes)
                val displayName = parsed?.subject?.takeIf { it.isNotBlank() } ?: filename
                
                // Store in repository
                val entry = repository.ingest(bytes, displayName, uri)
                
                // If successfully parsed, show the email
                if (parsed != null) {
                    _uiState.update { it.copy(
                        isLoading = false,
                        currentEmail = parsed
                    ) }
                } else {
                    _uiState.update { it.copy(
                        isLoading = false,
                        errorMessage = "Could not parse email"
                    ) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMessage = "Error: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Open a history entry for viewing.
     */
    fun openHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                // Update last accessed time
                repository.access(entry.id)
                
                // Load the file content
                val file = repository.blobFor(entry.blobHash)
                if (file != null && file.exists()) {
                    val bytes = file.readBytes()
                    val parsed = parseEmailBytes(bytes)
                    
                    if (parsed != null) {
                        _uiState.update { it.copy(
                            isLoading = false,
                            currentEmail = parsed
                        ) }
                    } else {
                        _uiState.update { it.copy(
                            isLoading = false,
                            errorMessage = "Could not parse email"
                        ) }
                    }
                } else {
                    _uiState.update { it.copy(
                        isLoading = false,
                        errorMessage = "Email file not found"
                    ) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isLoading = false,
                    errorMessage = "Error: ${e.message}"
                ) }
            }
        }
    }

    /**
     * Delete a history entry.
     */
    fun deleteHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            repository.delete(entry.id)
        }
    }

    /**
     * Clear all history entries.
     */
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    /**
     * Close the currently viewed email and return to history.
     */
    fun closeEmail() {
        _uiState.update { it.copy(currentEmail = null) }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Set an error message to display.
     */
    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /**
     * Parse email bytes using the Rust FFI via UniFFI bindings.
     * 
     * Uses stalwart's mail-parser for robust RFC 5322 parsing:
     * - Full MIME multipart support
     * - Proper character encoding (non-UTF8 charsets)
     * - Inline asset extraction for cid: URLs
     * - Memory-efficient opaque handle pattern
     */
    private suspend fun parseEmailBytes(bytes: ByteArray): EmailContent? {
        return withContext(Dispatchers.Default) {
            try {
                val handle: EmailHandle = parseEml(bytes)
                
                EmailContent(
                    subject = handle.subject(),
                    from = handle.from(),
                    to = handle.to(),
                    date = handle.date(),
                    bodyHtml = handle.bodyHtml(),
                    getResource = { cid -> handle.getResource(cid) }
                )
            } catch (e: ParseException) {
                // Rust parser returned a parse error - fall back to Kotlin parser
                parseEmailBytesKotlin(bytes)
            } catch (e: UnsatisfiedLinkError) {
                // Native library not available - fall back to Kotlin parser
                parseEmailBytesKotlin(bytes)
            } catch (e: ExceptionInInitializerError) {
                // Library initialization failed - fall back to Kotlin parser
                parseEmailBytesKotlin(bytes)
            }
        }
    }

    /**
     * Fallback Kotlin parser for cases where the native library is not available.
     */
    private fun parseEmailBytesKotlin(bytes: ByteArray): EmailContent? {
        return try {
            val text = String(bytes, Charsets.UTF_8)
            val headers = parseHeaders(text)
            val body = extractBody(text)
            
            val subject = headers["subject"] ?: "Untitled"
            val from = headers["from"] ?: ""
            val to = headers["to"] ?: ""
            val date = headers["date"] ?: ""
            
            // Convert plain text to basic HTML if needed
            val bodyHtml = if (body.startsWith("<")) {
                body
            } else {
                "<html><body><pre style=\"white-space: pre-wrap; font-family: sans-serif;\">${htmlEscape(body)}</pre></body></html>"
            }
            
            EmailContent(
                subject = subject,
                from = from,
                to = to,
                date = date,
                bodyHtml = bodyHtml,
                getResource = { _ -> null }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val headerSection = text.substringBefore("\n\n").substringBefore("\r\n\r\n")
        
        var currentKey = ""
        var currentValue = StringBuilder()
        
        for (line in headerSection.lines()) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // Continuation of previous header
                currentValue.append(" ").append(line.trim())
            } else if (line.contains(":")) {
                // Save previous header
                if (currentKey.isNotEmpty()) {
                    headers[currentKey.lowercase()] = currentValue.toString().trim()
                }
                // Start new header
                val colonIndex = line.indexOf(':')
                currentKey = line.substring(0, colonIndex)
                currentValue = StringBuilder(line.substring(colonIndex + 1).trim())
            }
        }
        
        // Save last header
        if (currentKey.isNotEmpty()) {
            headers[currentKey.lowercase()] = currentValue.toString().trim()
        }
        
        return headers
    }

    private fun extractBody(text: String): String {
        val body = if (text.contains("\r\n\r\n")) {
            text.substringAfter("\r\n\r\n")
        } else {
            text.substringAfter("\n\n")
        }
        return body.trim()
    }

    private fun htmlEscape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
