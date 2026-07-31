package org.joefang.letterbox

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import org.joefang.letterbox.ffi.EmailHandle
import org.joefang.letterbox.ffi.ParseException
import org.joefang.letterbox.ffi.parseEml
import org.joefang.letterbox.ffi.parseEmlFromPath
import org.joefang.letterbox.ffi.extractRemoteImages
import org.joefang.letterbox.ui.AttachmentData
import org.joefang.letterbox.ui.EmailContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import org.joefang.letterbox.data.SortField
import org.joefang.letterbox.data.SortDirection

/**
 * UI State for the main screen.
 */
data class EmailUiState(
    val currentEmail: EmailContent? = null,
    val currentEntryId: Long? = null,
    val currentEmailBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val sessionLoadImages: Boolean = false,
    val hasRemoteImages: Boolean = false,
    val cacheStats: CacheStats = CacheStats(0, 0L),
    // Search, filter, and sort state
    val searchQuery: String = "",
    val sortField: SortField = SortField.DATE,
    val sortDirection: SortDirection = SortDirection.DESCENDING,
    val filterHasAttachments: Boolean = false,
    val isSearchActive: Boolean = false
)

private const val TAG = "EmailViewModel"

/**
 * Outcome of parsing raw email bytes: the renderable [content] when parsing
 * succeeded, together with the [metadata] used to index the message for search.
 *
 * A failed parse yields a null [content] and empty metadata, never invented
 * values: whatever is indexed here persists, so guessing would outlive the
 * failure that caused it.
 */
private data class ParsedEmail(
    val content: EmailContent?,
    val metadata: EmailMetadata
)

class EmailViewModel(
    private val repository: HistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailUiState())
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.cacheStats.collect { stats ->
                _uiState.update { it.copy(cacheStats = stats) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The repository owns a scope for its startup reclamation sweep; without
        // this it would outlive the view model that created it.
        repository.close()
    }

    /**
     * The query described by this state's own search, filter and sort fields.
     */
    private fun EmailUiState.query(): HistoryQuery = HistoryQuery(
        text = searchQuery,
        onlyWithAttachments = filterHasAttachments,
        sortField = sortField,
        sortDirection = sortDirection
    )

    /**
     * The history list, paged from the database.
     *
     * `distinctUntilChanged` keeps a state change that does not affect the query —
     * entering search mode, an error appearing — from restarting paging and
     * scrolling the user back to the top. `flatMapLatest` abandons the previous
     * query's pages as soon as the query changes, so a fast typist is never
     * served results for an earlier prefix. `cachedIn` keeps loaded pages across
     * configuration changes and lets several collectors share one stream.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: Flow<PagingData<HistoryEntry>> = _uiState
        .map { it.query() }
        .distinctUntilChanged()
        .flatMapLatest { repository.pagedHistory(it) }
        .cachedIn(viewModelScope)
    
    // =========================================================================
    // Search, Filter, and Sort Methods
    // =========================================================================
    
    /**
     * Update the search query and re-filter results.
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Enter or leave search mode. Leaving clears the query.
     */
    fun setSearchActive(active: Boolean) {
        _uiState.update {
            if (active) {
                it.copy(isSearchActive = true)
            } else {
                it.copy(isSearchActive = false, searchQuery = "")
            }
        }
    }

    /**
     * Update the sort field and direction.
     */
    fun setSortOrder(field: SortField, direction: SortDirection) {
        _uiState.update {
            it.copy(sortField = field, sortDirection = direction)
        }
    }

    /**
     * Toggle the has-attachments filter.
     */
    fun toggleAttachmentsFilter() {
        _uiState.update {
            it.copy(filterHasAttachments = !it.filterHasAttachments)
        }
    }

    /**
     * Ingest email from a URI (content:// or file://).
     */
    fun ingestFromUri(bytes: ByteArray, filename: String, uri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                // Parse the email to extract metadata for indexing
                val (parsed, metadata) = parseEmailBytesWithMetadata(bytes)
                val displayName = parsed?.subject?.takeIf { it.isNotBlank() } ?: filename

                // Store in repository with metadata for search/filter
                val entry = repository.ingest(bytes, displayName, uri, metadata)

                // If successfully parsed, show the email
                if (parsed != null) {
                    // Detected before the update: `update` retries its lambda on
                    // a lost compare-and-set, and this crosses the FFI boundary.
                    val hasRemoteImages = detectRemoteImages(parsed.bodyHtml)
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
                        val hasRemoteImages = detectRemoteImages(parsed.bodyHtml)
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
     * Write every cached email into [output] as a zip, closing it when done.
     *
     * Suspends rather than launching, so the caller owns the scope: the export
     * writes into a stream the caller opened from a document the user picked, and
     * the caller is what reports progress and completion. Cancelling the caller's
     * coroutine cancels the export between messages.
     */
    suspend fun exportAll(
        output: OutputStream,
        onProgress: (processed: Int, total: Int) -> Unit
    ): ArchiveSummary = repository.exportAll(output, onProgress)

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
     * Whether [html] references at least one remote image, as judged by the Rust
     * FFI. Crosses the native boundary, so callers evaluate it once rather than
     * inside a `MutableStateFlow.update` lambda, which retries on a lost
     * compare-and-set.
     *
     * Total by construction: the native library can be absent or fail to
     * initialise (notably on a host JVM), and neither is something the user can
     * act on, so every failure answers "no remote images" rather than
     * propagating. `Error` subclasses are caught deliberately for that reason.
     */
    private fun detectRemoteImages(html: String?): Boolean =
        try {
            extractRemoteImages(html ?: "").isNotEmpty()
        } catch (e: UnsatisfiedLinkError) {
            false // Native library not available
        } catch (e: ExceptionInInitializerError) {
            false // Library initialization failed
        } catch (e: Exception) {
            false
        }

    /**
     * Parse email bytes and extract metadata for search/filter indexing, using
     * the Rust FFI via UniFFI bindings.
     *
     * Uses stalwart's mail-parser for robust RFC 5322 parsing:
     * - Full MIME multipart support
     * - Proper character encoding (non-UTF8 charsets)
     * - Inline asset extraction for cid: URLs
     * - Memory-efficient opaque handle pattern
     *
     * A message that will not parse yields no content and no metadata.
     */
    private suspend fun parseEmailBytesWithMetadata(bytes: ByteArray): ParsedEmail {
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
                
                // Extract structured sender info
                val senderInfo = try {
                    handle.senderInfo()
                } catch (e: Exception) {
                    null
                }
                
                // Extract recipient info for search
                val recipientInfo = try {
                    handle.recipientInfo()
                } catch (e: Exception) {
                    emptyList()
                }
                
                // Build metadata for search indexing
                val metadata = EmailMetadata(
                    subject = handle.subject(),
                    senderEmail = senderInfo?.email ?: "",
                    senderName = senderInfo?.name ?: "",
                    recipientEmails = recipientInfo.mapNotNull { it.email.takeIf { e -> e.isNotBlank() } }.joinToString(", "),
                    recipientNames = recipientInfo.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.joinToString(", "),
                    emailDate = try { handle.dateTimestamp() } catch (e: Exception) { 0L },
                    hasAttachments = attachments.isNotEmpty(),
                    bodyPreview = try { handle.bodyPreview() } catch (e: Exception) { "" }
                )
                
                val content = EmailContent(
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
                
                ParsedEmail(content, metadata)
            } catch (e: ParseException) {
                unparsed(e)
            } catch (e: UnsatisfiedLinkError) {
                unparsed(e)
            } catch (e: ExceptionInInitializerError) {
                unparsed(e)
            }
        }
    }

    /**
     * What a message that would not parse yields: nothing.
     *
     * This replaced a second, hand-written Kotlin parser. It ran whenever the
     * real one reported an error and rebuilt the message from a naive header
     * split — no date, no attachments, no inline images — then wrote that into
     * the search index, where it stayed. A transient failure became permanent
     * mis-indexing, and the user was never told.
     */
    private fun unparsed(cause: Throwable): ParsedEmail {
        Log.w(TAG, "Could not parse message", cause)
        return ParsedEmail(null, EmailMetadata())
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
                unparsed(e).content
            } catch (e: UnsatisfiedLinkError) {
                unparsed(e).content
            } catch (e: ExceptionInInitializerError) {
                unparsed(e).content
            }
        }
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
