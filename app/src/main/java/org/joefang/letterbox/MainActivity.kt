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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.joefang.letterbox.ui.EmailContent
import org.joefang.letterbox.ui.EmailDetailScreen
import org.joefang.letterbox.ui.theme.LetterboxTheme
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MIME types accepted by the file picker for email files.
 */
private val EMAIL_MIME_TYPES = arrayOf("message/rfc822", "application/eml", "*/*")

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
        EmailViewModelFactory(InMemoryHistoryRepository(filesDir))
    }
    
    private var launchedFromExternalIntent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                                }
                            )
                        }
                        else -> {
                            LetterboxScaffold(
                                history = uiState.history,
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
                                snackbarHostState = snackbarHostState
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
    onEntryClick: (HistoryEntry) -> Unit,
    onEntryDelete: (HistoryEntry) -> Unit,
    onOpenFile: (Uri) -> Unit,
    onClearHistory: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsSheetState = rememberModalBottomSheetState()
    
    // Settings state
    // TODO: Implement proper persistence using DataStore or SharedPreferences
    // For now, settings are stored in memory and reset on app restart
    var historyLimitIndex by remember { mutableIntStateOf(0) } // 0=10, 1=20, 2=50, 3=100, 4=Unlimited
    var storeLocalCopies by remember { mutableStateOf(true) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onOpenFile(it) }
    }
    
    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text(text = "Letterbox") },
                actions = {
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
                            text = { Text("Clear history") },
                            onClick = {
                                showMenu = false
                                showClearHistoryDialog = true
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
            
            // History list
            HistoryList(
                entries = history,
                onEntryClick = onEntryClick,
                onEntryDelete = onEntryDelete,
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
                historyLimitIndex = historyLimitIndex,
                onHistoryLimitChange = { historyLimitIndex = it },
                storeLocalCopies = storeLocalCopies,
                onStoreLocalCopiesChange = { storeLocalCopies = it },
                onClearHistory = {
                    showSettingsSheet = false
                    showClearHistoryDialog = true
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
    
    // Clear history confirmation dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear history?") },
            text = { Text("This will remove all items from your history. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        onClearHistory()
                        scope.launch {
                            snackbarHostState.showSnackbar("History cleared")
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsContent(
    historyLimitIndex: Int,
    onHistoryLimitChange: (Int) -> Unit,
    storeLocalCopies: Boolean,
    onStoreLocalCopiesChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit
) {
    val historyLimitOptions = listOf("10", "20", "50", "100", "Unlimited")
    
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
        
        // History limit setting
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "History limit",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Maximum number of recent emails to keep: ${historyLimitOptions[historyLimitIndex]}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = historyLimitIndex.toFloat(),
                onValueChange = { onHistoryLimitChange(it.toInt()) },
                valueRange = 0f..4f,
                steps = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        // Store local copies toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Store local copies",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Keep copies of emails for reliable history access",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = storeLocalCopies,
                onCheckedChange = onStoreLocalCopiesChange
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        
        // Clear history button
        TextButton(
            onClick = onClearHistory,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Clear history",
                color = MaterialTheme.colorScheme.error
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    onEntryDelete: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Open an .eml or .msg file to get started.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(entries) { entry ->
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
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = formatTimestamp(entry.lastAccessed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.originalUri?.let { uri ->
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        IconButton(onClick = onDelete) {
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
