package com.btreemap.letterbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.btreemap.letterbox.ui.theme.LetterboxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EmailViewModel by viewModels {
        EmailViewModelFactory(HistoryRepository(filesDir))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LetterboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val history by viewModel.history.collectAsState()
                    LetterboxScaffold(history)
                }
            }
        }
    }
}

@Composable
private fun LetterboxScaffold(history: List<HistoryEntry>) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(text = "Letterbox") }) }
    ) { padding ->
        HistoryList(
            entries = history,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

@Composable
private fun HistoryList(entries: List<HistoryEntry>, modifier: Modifier = Modifier) {
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
                HistoryRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Column(modifier = Modifier
        .fillMaxWidth()
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
            )
        )
    }
}
