package org.joefang.letterbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.joefang.letterbox.ffi.EmailHandle
import org.joefang.letterbox.ffi.ParseException
import org.joefang.letterbox.ffi.parseEml
import org.joefang.letterbox.ffi.parseEmlFromPath
import org.joefang.letterbox.ffi.extractRemoteImages
import org.joefang.letterbox.ui.AttachmentData
import org.joefang.letterbox.ui.EmailContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI State for the main screen.
 */
data class EmailUiState(
    val history: List<HistoryEntry> = emptyList(),
    val currentEmail: EmailContent? = null,
    val currentEntryId: Long? = null,
    val currentEmailBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val sessionLoadImages: Boolean = false,
    val hasRemoteImages: Boolean = false,
    val cacheStats: CacheStats = CacheStats(0, 0L)
)

class EmailViewModel(
    private val repository: HistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    init {
        // Observe history changes
        viewModelScope.launch {
            repository.items.collect { items ->
                _uiState.update { it.copy(history = items) }
                // Refresh cache stats whenever history changes
                refreshCacheStats()
            }
        }
    }
    
    /**
     * Refresh cache statistics.
     */
    private fun refreshCacheStats() {
        viewModelScope.launch {
            val stats = repository.getCacheStats()
            _uiState.update { it.copy(cacheStats = stats) }
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
                    // Use Rust FFI to detect remote images
                    // Note: Must catch both Exception and Error (UnsatisfiedLinkError)
                    // to gracefully handle cases where native library is unavailable
                    val hasRemoteImages = try {
                        val remoteImages = extractRemoteImages(parsed.bodyHtml ?: "")
                        remoteImages.isNotEmpty()
                    } catch (e: Exception) {
                        false
                    } catch (e: UnsatisfiedLinkError) {
                        // Native library not available
                        false
                    } catch (e: ExceptionInInitializerError) {
                        // Library initialization failed
                        false
                    }
                    _uiState.update { it.copy(
                        isLoading = false,
                        currentEmail = parsed,
                        currentEntryId = entry.id,
                        currentEmailBytes = bytes,
                        sessionLoadImages = false,
                        hasRemoteImages = hasRemoteImages
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
     * Uses path-based parsing when available to avoid copying file into JVM heap.
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
                    // Use path-based parsing to avoid JVM heap allocation during parsing
                    val parsed = parseEmailFromPath(file)
                    
                    if (parsed != null) {
                        // Read bytes for sharing functionality
                        // Note: This could be optimized to load lazily only when sharing
                        val bytes = file.readBytes()
                        // Use Rust FFI to detect remote images
                        // Note: Must catch both Exception and Error (UnsatisfiedLinkError)
                        // to gracefully handle cases where native library is unavailable
                        val hasRemoteImages = try {
                            val remoteImages = extractRemoteImages(parsed.bodyHtml ?: "")
                            remoteImages.isNotEmpty()
                        } catch (e: Exception) {
                            false
                        } catch (e: UnsatisfiedLinkError) {
                            // Native library not available
                            false
                        } catch (e: ExceptionInInitializerError) {
                            // Library initialization failed
                            false
                        }
                        _uiState.update { it.copy(
                            isLoading = false,
                            currentEmail = parsed,
                            currentEntryId = entry.id,
                            currentEmailBytes = bytes,
                            sessionLoadImages = false,
                            hasRemoteImages = hasRemoteImages
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
     * Remove the current email from history and close the viewer.
     */
    fun removeCurrentFromHistory() {
        val entryId = _uiState.value.currentEntryId ?: return
        viewModelScope.launch {
            repository.delete(entryId)
            _uiState.update { it.copy(
                currentEmail = null,
                currentEntryId = null,
                currentEmailBytes = null
            ) }
        }
    }

    /**
     * Get the current email bytes for sharing.
     */
    fun getCurrentEmailBytes(): ByteArray? {
        return _uiState.value.currentEmailBytes
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
        _uiState.update { it.copy(
            currentEmail = null,
            currentEntryId = null,
            currentEmailBytes = null,
            sessionLoadImages = false,
            hasRemoteImages = false
        ) }
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
     * Enable image loading for the current session.
     * This is a one-time action that persists until the email is closed.
     */
    fun enableSessionImageLoading() {
        _uiState.update { it.copy(sessionLoadImages = true) }
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
                
                // Convert FFI attachments to UI attachment data
                val attachments = handle.getAttachments().mapIndexed { index, info ->
                    AttachmentData(
                        name = info.name,
                        contentType = info.contentType,
                        size = info.size.toLong(),
                        index = index
                    )
                }
                
                EmailContent(
                    subject = handle.subject(),
                    from = handle.from(),
                    to = handle.to(),
                    cc = handle.cc(),
                    replyTo = handle.replyTo(),
                    messageId = handle.messageId(),
                    date = handle.date(),
                    bodyHtml = handle.bodyHtml(),
                    attachments = attachments,
                    getResource = { cid -> handle.getResource(cid) },
                    getAttachmentContent = { index -> handle.getAttachmentContent(index.toUInt()) }
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
     * Parse email from a file path using the Rust FFI via UniFFI bindings.
     * 
     * This is the preferred method for large emails as it avoids copying the entire
     * file into the JVM heap. Rust reads/mmaps the file directly.
     */
    private suspend fun parseEmailFromPath(file: File): EmailContent? {
        return withContext(Dispatchers.Default) {
            try {
                val handle: EmailHandle = parseEmlFromPath(file.absolutePath)
                
                // Convert FFI attachments to UI attachment data
                val attachments = handle.getAttachments().mapIndexed { index, info ->
                    AttachmentData(
                        name = info.name,
                        contentType = info.contentType,
                        size = info.size.toLong(),
                        index = index
                    )
                }
                
                EmailContent(
                    subject = handle.subject(),
                    from = handle.from(),
                    to = handle.to(),
                    cc = handle.cc(),
                    replyTo = handle.replyTo(),
                    messageId = handle.messageId(),
                    date = handle.date(),
                    bodyHtml = handle.bodyHtml(),
                    attachments = attachments,
                    getResource = { cid -> handle.getResource(cid) },
                    getAttachmentContent = { index -> handle.getAttachmentContent(index.toUInt()) }
                )
            } catch (e: ParseException) {
                // Rust parser returned a parse error - fall back to Kotlin parser
                parseEmailBytesKotlin(file.readBytes())
            } catch (e: UnsatisfiedLinkError) {
                // Native library not available - fall back to Kotlin parser
                parseEmailBytesKotlin(file.readBytes())
            } catch (e: ExceptionInInitializerError) {
                // Library initialization failed - fall back to Kotlin parser
                parseEmailBytesKotlin(file.readBytes())
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
            val cc = headers["cc"] ?: ""
            val replyTo = headers["reply-to"] ?: ""
            val messageId = headers["message-id"] ?: ""
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
                cc = cc,
                replyTo = replyTo,
                messageId = messageId,
                date = date,
                bodyHtml = bodyHtml,
                attachments = emptyList(), // Fallback parser doesn't extract attachments
                getResource = { _ -> null },
                getAttachmentContent = { _ -> null }
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
    private val repository: HistoryRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EmailViewModel::class.java)) {
            return EmailViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
