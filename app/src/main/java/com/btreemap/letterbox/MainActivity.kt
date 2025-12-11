package com.btreemap.letterbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.btreemap.letterbox.ui.EmailContent
import com.btreemap.letterbox.ui.EmailDetailScreen
import com.btreemap.letterbox.ui.theme.LetterboxTheme
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private val viewModel: EmailViewModel by viewModels {
        EmailViewModelFactory(InMemoryHistoryRepository(filesDir))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                            EmailDetailScreen(
                                email = uiState.currentEmail!!,
                                onNavigateBack = { viewModel.closeEmail() }
                            )
                        }
                        else -> {
                            LetterboxScaffold(
                                history = uiState.history,
                                onEntryClick = { entry ->
                                    viewModel.openHistoryEntry(entry)
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
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                loadEmailFromUri(uri, intent.type)
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
    snackbarHostState: SnackbarHostState
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Letterbox") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        HistoryList(
            entries = history,
            onEntryClick = onEntryClick,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
            Text(
                text = "No history yet. Open an .eml or .msg file to get started.",
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
                    onClick = { onEntryClick(entry) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = entry.originalUri ?: "Local file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
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
            onEntryClick = {}
        )
    }
}
