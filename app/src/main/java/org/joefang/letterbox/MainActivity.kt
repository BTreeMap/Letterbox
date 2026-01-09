package org.joefang.letterbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.joefang.letterbox.data.LetterboxDatabase
import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import org.joefang.letterbox.data.UserPreferencesRepository
import org.joefang.letterbox.ui.EmailDetailScreen
import org.joefang.letterbox.ui.theme.LetterboxTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MIME types accepted by the file picker for email files.
 * Restricted to specific types to reduce user error.
 */
private val EMAIL_MIME_TYPES = arrayOf("message/rfc822", "application/octet-stream", "text/plain")

/**
 * Time constants for relative timestamp formatting (in milliseconds).
 */
private object TimeConstants {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 604_800_000L
}

/**
 * File sharing constants.
 */
private object FileConstants {
    const val MAX_FILENAME_LENGTH = 50
}

class MainActivity : ComponentActivity() {
    private val viewModel: EmailViewModel by viewModels {
        val database = LetterboxDatabase.getInstance(this)
        EmailViewModelFactory(
            HistoryRepository(
                baseDir = filesDir,
                blobDao = database.blobDao(),
                historyItemDao = database.historyItemDao()
            )
        )
    }
    
    private lateinit var preferencesRepository: UserPreferencesRepository
    private var launchedFromExternalIntent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize preferences repository
        preferencesRepository = UserPreferencesRepository(this)
        
        // Track if launched from external VIEW or SEND intent
        launchedFromExternalIntent = intent?.action == Intent.ACTION_VIEW || 
            intent?.action == Intent.ACTION_SEND
        
        // Handle incoming intent
        handleIntent(intent)
        
        setContent {
            LetterboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()
                    val snackbarHostState = remember { SnackbarHostState() }
                    
                    // Collect preferences
                    val enablePrivacyProxy by preferencesRepository.enablePrivacyProxy.collectAsState(initial = true)
                    val alwaysLoadRemoteImages by preferencesRepository.alwaysLoadRemoteImages.collectAsState(initial = false)

                    // Show error message if any
                    LaunchedEffect(uiState.errorMessage) {
                        uiState.errorMessage?.let { message ->
                            snackbarHostState.showSnackbar(message)
                            viewModel.clearError()
                        }
                    }

                    when {
                        uiState.isLoading -> {
                            LoadingScreen()
                        }
                        uiState.currentEmail != null -> {
                            val context = LocalContext.current
                            val scope = rememberCoroutineScope()
                            
                            // Determine whether images should be loaded
                            val shouldLoadImages = alwaysLoadRemoteImages || uiState.sessionLoadImages
                            
                            EmailDetailScreen(
                                email = uiState.currentEmail!!,
                                onNavigateBack = { 
                                    if (launchedFromExternalIntent) {
                                        finish()
                                    } else {
                                        viewModel.closeEmail()
                                    }
                                },
                                onRemoveFromHistory = if (!launchedFromExternalIntent) {
                                    {
                                        viewModel.removeCurrentFromHistory()
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Removed from history")
                                        }
                                    }
                                } else null,
                                onShareEml = {
                                    shareCurrentEmail(context, uiState.currentEmail!!.subject)
                                },
                                hasRemoteImages = uiState.hasRemoteImages,
                                sessionLoadImages = shouldLoadImages,
                                onShowImages = {
                                    viewModel.enableSessionImageLoading()
                                },
                                useProxy = enablePrivacyProxy
                            )
                        }
                        else -> {
                            LetterboxScaffold(
                                history = uiState.filteredHistory,
                                cacheStats = uiState.cacheStats,
                                searchQuery = uiState.searchQuery,
                                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                                isSearchActive = uiState.isSearchActive,
                                onSearchActiveChange = { viewModel.setSearchActive(it) },
                                sortField = uiState.sortField,
                                sortDirection = uiState.sortDirection,
                                onSortChange = { field, direction -> viewModel.setSortOrder(field, direction) },
                                filterHasAttachments = uiState.filterHasAttachments,
                                onToggleAttachmentsFilter = { viewModel.toggleAttachmentsFilter() },
                                onEntryClick = { entry ->
                                    viewModel.openHistoryEntry(entry)
                                },
                                onEntryDelete = { entry ->
                                    viewModel.deleteHistoryEntry(entry)
                                },
                                onOpenFile = { uri ->
                                    loadEmailFromUri(uri, null)
                                },
                                onClearHistory = {
                                    viewModel.clearHistory()
                                },
                                snackbarHostState = snackbarHostState,
                                preferencesRepository = preferencesRepository
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchedFromExternalIntent = intent.action == Intent.ACTION_VIEW ||
            intent.action == Intent.ACTION_SEND
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    loadEmailFromUri(uri, intent.type)
                }
            }
            Intent.ACTION_SEND -> {
                // Handle "Share" / "Send" intents - get URI from EXTRA_STREAM
                @Suppress("DEPRECATION")
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { loadEmailFromUri(it, intent.type) }
            }
        }
    }

    private fun loadEmailFromUri(uri: Uri, mimeType: String?) {
        try {
            // Take persistable URI permission for content:// URIs
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Permission not available - this is OK, file may still be readable
                }
            }
            
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                val filename = getFilenameFromUri(uri) ?: "email.eml"
                viewModel.ingestFromUri(bytes, filename, uri.toString())
            }
        } catch (e: Exception) {
            viewModel.setError("Failed to open email: ${e.message}")
        }
    }

    private fun getFilenameFromUri(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        }
    }

    private fun shareCurrentEmail(context: android.content.Context, subject: String) {
        val bytes = viewModel.getCurrentEmailBytes() ?: return
        
        try {
            // Save to cache directory
            val cacheDir = File(context.cacheDir, "shared")
            cacheDir.mkdirs()
            val filename = "${subject.take(FileConstants.MAX_FILENAME_LENGTH).replace(Regex("[^a-zA-Z0-9]"), "_")}.eml"
            val file = File(cacheDir, filename)
            file.writeBytes(bytes)
            
            // Create content URI via FileProvider
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            // Create share intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share email"))
        } catch (e: Exception) {
            viewModel.setError("Failed to share email: ${e.message}")
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LetterboxScaffold(
    history: List<HistoryEntry>,
    cacheStats: CacheStats,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    sortField: SortField,
    sortDirection: SortDirection,
    onSortChange: (SortField, SortDirection) -> Unit,
    filterHasAttachments: Boolean,
    onToggleAttachmentsFilter: () -> Unit,
    onEntryClick: (HistoryEntry) -> Unit,
    onEntryDelete: (HistoryEntry) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onClearHistory: () -> Unit,
    snackbarHostState: SnackbarHostState,
    preferencesRepository: UserPreferencesRepository
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsSheetState = rememberModalBottomSheetState()
    val searchFocusRequester = remember { FocusRequester() }
    
    // Collect image loading preferences
    val alwaysLoadRemoteImages by preferencesRepository.alwaysLoadRemoteImages.collectAsState(initial = false)
    val enablePrivacyProxy by preferencesRepository.enablePrivacyProxy.collectAsState(initial = true)
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onOpenFile(it) }
    }
    
    // Request focus when search becomes active
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }
    
    Scaffold(
        topBar = { 
            if (isSearchActive) {
                // Search mode top bar
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search emails...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .testTag("searchTextField")
                                .semantics { contentDescription = "Search emails" },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.semantics { contentDescription = "Clear search" }
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                    }
                                }
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { onSearchActiveChange(false) },
                            modifier = Modifier.semantics { contentDescription = "Close search" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                // Normal mode top bar
                TopAppBar(
                    title = { Text(text = "Letterbox") },
                    actions = {
                        IconButton(
                            onClick = { onSearchActiveChange(true) },
                            modifier = Modifier.semantics { contentDescription = "Search emails" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    showSettingsSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Open file button
            Button(
                onClick = {
                    filePickerLauncher.launch(EMAIL_MIME_TYPES)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Open file")
            }
            
            // Sort and Filter controls
            if (history.isNotEmpty() || isSearchActive || filterHasAttachments) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sort button with dropdown
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { showSortMenu = true },
                            label = {
                                Text(
                                    when (sortField) {
                                        SortField.DATE -> "Date"
                                        SortField.SUBJECT -> "Subject"
                                        SortField.SENDER -> "Sender"
                                    } + if (sortDirection == SortDirection.ASCENDING) " ↑" else " ↓"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = "Sort emails" }
                        )
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Date (newest first)") },
                                onClick = {
                                    onSortChange(SortField.DATE, SortDirection.DESCENDING)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Date (oldest first)") },
                                onClick = {
                                    onSortChange(SortField.DATE, SortDirection.ASCENDING)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Subject (A-Z)") },
                                onClick = {
                                    onSortChange(SortField.SUBJECT, SortDirection.ASCENDING)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Subject (Z-A)") },
                                onClick = {
                                    onSortChange(SortField.SUBJECT, SortDirection.DESCENDING)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sender (A-Z)") },
                                onClick = {
                                    onSortChange(SortField.SENDER, SortDirection.ASCENDING)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sender (Z-A)") },
                                onClick = {
                                    onSortChange(SortField.SENDER, SortDirection.DESCENDING)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                    
                    // Attachments filter chip
                    FilterChip(
                        selected = filterHasAttachments,
                        onClick = onToggleAttachmentsFilter,
                        label = { 
                            Text(if (filterHasAttachments) "📎 Attachments" else "Has attachments") 
                        },
                        modifier = Modifier.semantics { 
                            contentDescription = if (filterHasAttachments) 
                                "Filter: showing only emails with attachments" 
                            else 
                                "Filter by attachments"
                        }
                    )
                }
            }
            
            // History list with appropriate empty message
            val emptyMessage = when {
                isSearchActive && searchQuery.isNotBlank() -> "No emails match \"$searchQuery\""
                filterHasAttachments -> "No emails with attachments"
                else -> "Open an .eml or .msg file to get started."
            }
            
            HistoryList(
                entries = history,
                onEntryClick = onEntryClick,
                onEntryDelete = onEntryDelete,
                emptyMessage = emptyMessage,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    
    // Settings bottom sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = settingsSheetState
        ) {
            SettingsContent(
                cacheStats = cacheStats,
                alwaysLoadRemoteImages = alwaysLoadRemoteImages,
                onAlwaysLoadRemoteImagesChange = { 
                    scope.launch { 
                        preferencesRepository.setAlwaysLoadRemoteImages(it)
                    }
                },
                enablePrivacyProxy = enablePrivacyProxy,
                onEnablePrivacyProxyChange = {
                    scope.launch {
                        preferencesRepository.setEnablePrivacyProxy(it)
                    }
                },
                onClearCache = {
                    showSettingsSheet = false
                    showClearCacheDialog = true
                }
            )
        }
    }
    
    // About dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Letterbox") },
            text = { 
                Text(
                    "Letterbox is a privacy-focused .eml and .msg file viewer.\n\n" +
                    "• Zero network permissions\n" +
                    "• Secure sandboxed rendering\n" +
                    "• Powered by Rust mail-parser"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
    
    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear cache?") },
            text = { 
                Text(
                    "This will delete all ${cacheStats.entryCount} cached emails " +
                    "(${formatStorageSize(cacheStats.totalSizeBytes)}). This action cannot be undone."
                ) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        onClearHistory()
                        scope.launch {
                            snackbarHostState.showSnackbar("Cache cleared")
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsContent(
    cacheStats: CacheStats,
    alwaysLoadRemoteImages: Boolean,
    onAlwaysLoadRemoteImagesChange: (Boolean) -> Unit,
    enablePrivacyProxy: Boolean,
    onEnablePrivacyProxyChange: (Boolean) -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Remote images section
        Text(
            text = "Remote Images",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        
        // Always load remote images toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Always load remote images",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Automatically load images from external sources",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = alwaysLoadRemoteImages,
                onCheckedChange = onAlwaysLoadRemoteImagesChange
            )
        }
        
        // Privacy proxy toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Use privacy proxy",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Load images through a privacy proxy to hide your IP address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enablePrivacyProxy,
                onCheckedChange = onEnablePrivacyProxyChange
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        // Storage section (Telegram-style clear cache)
        Text(
            text = "Storage",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        
        // Cache info card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Email cache",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (cacheStats.entryCount == 0) {
                        "No cached emails"
                    } else {
                        "${cacheStats.entryCount} email${if (cacheStats.entryCount != 1) "s" else ""} • ${formatStorageSize(cacheStats.totalSizeBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onClearCache,
                enabled = cacheStats.entryCount > 0
            ) {
                Text(
                    text = "Clear",
                    color = if (cacheStats.entryCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Format storage size in a human-readable format.
 */
private fun formatStorageSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    onEntryDelete: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Open an .eml or .msg file to get started."
) {
    if (entries.isEmpty()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    onClick = { onEntryClick(entry) },
                    onDelete = { onEntryDelete(entry) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry, 
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics { 
                contentDescription = buildString {
                    append("Email: ${entry.displayName}")
                    if (entry.displaySender.isNotBlank()) {
                        append(", from ${entry.displaySender}")
                    }
                    if (entry.hasAttachments) {
                        append(", has attachments")
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Subject/display name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Attachment indicator
                if (entry.hasAttachments) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "📎",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            
            // Sender info (if available)
            if (entry.displaySender.isNotBlank()) {
                Text(
                    text = entry.displaySender,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            
            // Timestamp and source
            Row {
                Text(
                    text = formatTimestamp(entry.effectiveDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                entry.originalUri?.let { uri ->
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = extractSourceName(uri),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.semantics { contentDescription = "Delete email" }
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < TimeConstants.MINUTE_MS -> "Just now"
        diff < TimeConstants.HOUR_MS -> "${diff / TimeConstants.MINUTE_MS}m ago"
        diff < TimeConstants.DAY_MS -> "${diff / TimeConstants.HOUR_MS}h ago"
        diff < TimeConstants.WEEK_MS -> "${diff / TimeConstants.DAY_MS}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

private fun extractSourceName(uri: String): String {
    return when {
        uri.startsWith("content://com.google.android.gm") -> "Gmail"
        uri.startsWith("content://com.google.android.apps.docs") -> "Drive"
        uri.startsWith("content://com.android.providers.downloads") -> "Downloads"
        uri.startsWith("content://media/") -> "Files"
        else -> "External"
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryList() {
    LetterboxTheme {
        HistoryList(
            entries = listOf(
                HistoryEntry(
                    id = 1,
                    blobHash = "abc",
                    displayName = "Sample Email",
                    originalUri = "content://email/1",
                    lastAccessed = System.currentTimeMillis()
                )
            ),
            onEntryClick = {},
            onEntryDelete = {}
        )
    }
}
