package org.joefang.letterbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import org.joefang.letterbox.data.LetterboxDatabase
import org.joefang.letterbox.data.SortDirection
import org.joefang.letterbox.data.SortField
import org.joefang.letterbox.data.UserPreferencesRepository
import org.joefang.letterbox.data.ImageProxyService
import org.joefang.letterbox.ffi.proxy.UpdateResult
import org.joefang.letterbox.ui.DEFAULT_EMAIL_FILENAME
import org.joefang.letterbox.ui.EmailDetailScreen
import org.joefang.letterbox.ui.DiagnosticsDialog
import org.joefang.letterbox.ui.OnboardingScreen
import org.joefang.letterbox.ui.UpdateAvailableDialog
import org.joefang.letterbox.ui.UpToDateDialog
import org.joefang.letterbox.ui.exportArchiveName
import org.joefang.letterbox.ui.formatRelativeTimestamp
import org.joefang.letterbox.ui.formatStorageSize
import org.joefang.letterbox.ui.sharedEmailFilename
import org.joefang.letterbox.ui.sourceLabel
import org.joefang.letterbox.ui.theme.LetterboxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

/**
 * MIME types accepted by the file picker for email files.
 * Restricted to specific types to reduce user error.
 */
private val EMAIL_MIME_TYPES = arrayOf("message/rfc822", "application/octet-stream", "text/plain")

/**
 * Intent actions that mean another app handed us an email to display. Typed as a
 * set of nullable strings because "this intent has no action" is a member of the
 * domain being tested against.
 */
private val EXTERNAL_EMAIL_ACTIONS: Set<String?> = setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)

/** Minimum interval between automatic update checks (24 hours). */
private const val UPDATE_CHECK_INTERVAL_MS = 86_400_000L

/**
 * The URI of the email this intent asks us to open, or `null` if it asks for no
 * such thing. `ACTION_VIEW` carries it in the data field and `ACTION_SEND` in
 * `EXTRA_STREAM`; every other action yields `null`, so the function is total.
 */
private fun Intent.emailUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> streamExtra()
    else -> null
}

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

/**
 * Whether this intent came from another app handing us an email, which decides
 * whether "back" closes the app or returns to the local history list.
 *
 * Deliberately independent of [emailUri]: a malformed external intent that
 * carries no URI still arrived from outside, and back should still exit.
 */
private fun Intent?.isExternalEmailLaunch(): Boolean = this?.action in EXTERNAL_EMAIL_ACTIONS

/**
 * The preference values the home screen renders, collected once so every
 * consumer sees the same value — including the same value before DataStore has
 * emitted. Collecting one flow in two places with two different `initial`
 * values is what previously made the settings sheet show the privacy proxy as
 * off while the rest of the app treated it as on.
 */
private data class AppPreferences(
    val alwaysLoadRemoteImages: Boolean,
    val enablePrivacyProxy: Boolean,
    val cloudflareTermsAccepted: Boolean,
)

/**
 * State of the "Check for updates" flow.
 *
 * [Idle] and [Checking] describe the UI only; [UpToDate], [Available] and
 * [Failed] are exactly the outcomes a check can produce, so one closed type
 * serves as both the result of [MainActivity.runUpdateCheck] and the state the
 * dialogs eliminate.
 */
private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String) : UpdateCheckState
    data class Available(val result: UpdateResult) : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

/** MIME type of an exported mailbox archive. */
private const val ARCHIVE_MIME_TYPE = "application/zip"

/**
 * State of the "export all emails" flow.
 *
 * [Running] carries counts rather than a bare flag so the dialog can show real
 * progress: exporting an unbounded cache is not instant, and a spinner that says
 * nothing about how far along it is reads as a hang.
 */
private sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val processed: Int, val total: Int) : ExportState
    data class Done(val summary: ArchiveSummary) : ExportState
    data class Failed(val message: String) : ExportState
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

    /**
     * Whether the current intent came from another app. Snapshot state rather
     * than a plain field: `onNewIntent` can flip it while the UI is composed,
     * and the detail screen's back and "remove from history" affordances are
     * derived from it, so a plain field would leave them stale.
     */
    private var launchedFromExternalIntent by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesRepository = UserPreferencesRepository(this)
        launchedFromExternalIntent = intent.isExternalEmailLaunch()
        handleIntent(intent)

        setContent {
            LetterboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LetterboxApp()
                }
            }
        }
    }

    /**
     * Root of the composable tree. Owns the screen-level state and routes every
     * effect back to a method on this activity, so the composables below stay
     * functions of plain values and callbacks.
     */
    @Composable
    private fun LetterboxApp() {
        val uiState by viewModel.uiState.collectAsState()
        val history = viewModel.history.collectAsLazyPagingItems()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        // One collection site per preference. Each `initial` matches the default
        // the repository itself falls back to, so the pre-emission frame agrees
        // with the persisted value.
        val onboardingCompleted by preferencesRepository.onboardingCompleted
            .collectAsState(initial = true)
        val alwaysLoadRemoteImages by preferencesRepository.alwaysLoadRemoteImages
            .collectAsState(initial = false)
        val enablePrivacyProxy by preferencesRepository.enablePrivacyProxy
            .collectAsState(initial = true)
        val cloudflareTermsAccepted by preferencesRepository.cloudflareTermsAccepted
            .collectAsState(initial = false)

        val preferences = AppPreferences(
            alwaysLoadRemoteImages = alwaysLoadRemoteImages,
            enablePrivacyProxy = enablePrivacyProxy,
            cloudflareTermsAccepted = cloudflareTermsAccepted
        )

        var updateCheckState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
        var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }

        // The user names and places the archive through the system document
        // picker, and the export streams straight into the stream it returns —
        // so a full mailbox is never staged in the app's own cache first.
        val exportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(ARCHIVE_MIME_TYPE)
        ) { destination: Uri? ->
            if (destination == null) {
                // Picker dismissed; nothing was created.
                exportState = ExportState.Idle
            } else {
                scope.launch {
                    exportState = ExportState.Running(processed = 0, total = 0)
                    exportState = exportArchiveTo(destination) { processed, total ->
                        exportState = ExportState.Running(processed, total)
                    }
                }
            }
        }

        // First-launch onboarding establishes network consent.
        if (!onboardingCompleted) {
            OnboardingScreen(
                onAccept = {
                    scope.launch { preferencesRepository.completeOnboarding(acceptedTerms = true) }
                },
                onDecline = {
                    scope.launch { preferencesRepository.completeOnboarding(acceptedTerms = false) }
                }
            )
            return
        }

        // Throttled, silent update check on launch (once per day), only when the
        // user has consented to tunnelled networking.
        if (cloudflareTermsAccepted) {
            LaunchedEffect(Unit) {
                maybeAutoCheckForUpdate(snackbarHostState)
            }
        }

        LaunchedEffect(uiState.errorMessage) {
            uiState.errorMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }

        // Export feedback. Running shows a dialog rather than nothing, so a long
        // export cannot be mistaken for a dead button; terminal states report once
        // and reset.
        when (val export = exportState) {
            is ExportState.Running -> ExportProgressDialog(export.processed, export.total)
            is ExportState.Done -> LaunchedEffect(export) {
                snackbarHostState.showSnackbar(exportSummaryMessage(export.summary))
                exportState = ExportState.Idle
            }
            is ExportState.Failed -> LaunchedEffect(export) {
                snackbarHostState.showSnackbar("Export failed: ${export.message}")
                exportState = ExportState.Idle
            }
            ExportState.Idle -> Unit
        }

        // Bind the optional email once: the branch then smart-casts, and the
        // detail screen and its share action are guaranteed to see the same
        // value. Re-reading `uiState.currentEmail` inside a callback is a fresh
        // nullable read that can observe a later, empty state.
        val currentEmail = uiState.currentEmail

        when {
            uiState.isLoading -> LoadingScreen()

            currentEmail != null -> EmailDetailScreen(
                email = currentEmail,
                onNavigateBack = {
                    if (launchedFromExternalIntent) finish() else viewModel.closeEmail()
                },
                onRemoveFromHistory = if (launchedFromExternalIntent) {
                    null
                } else {
                    {
                        viewModel.removeCurrentFromHistory()
                        scope.launch { snackbarHostState.showSnackbar("Removed from history") }
                    }
                },
                onShareEml = { shareCurrentEmail(currentEmail.subject) },
                hasRemoteImages = uiState.hasRemoteImages,
                sessionLoadImages = preferences.alwaysLoadRemoteImages || uiState.sessionLoadImages,
                onShowImages = { viewModel.enableSessionImageLoading() },
                useProxy = preferences.enablePrivacyProxy,
                cloudflareTermsAccepted = preferences.cloudflareTermsAccepted
            )

            else -> LetterboxScaffold(
                history = history,
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
                onEntryClick = { entry -> viewModel.openHistoryEntry(entry) },
                onEntryDelete = { entry -> viewModel.deleteHistoryEntry(entry) },
                onOpenFile = { uri -> loadEmailFromUri(uri) },
                onClearHistory = { viewModel.clearHistory() },
                onExportAll = {
                    exportLauncher.launch(exportArchiveName(System.currentTimeMillis()))
                },
                preferences = preferences,
                onAlwaysLoadRemoteImagesChange = { enabled ->
                    scope.launch { preferencesRepository.setAlwaysLoadRemoteImages(enabled) }
                },
                onEnablePrivacyProxyChange = { enabled ->
                    scope.launch { preferencesRepository.setEnablePrivacyProxy(enabled) }
                },
                onAcceptCloudflareTerms = {
                    scope.launch { preferencesRepository.acceptCloudflareTermsAndEnableProxy() }
                },
                updateCheckState = updateCheckState,
                onCheckForUpdate = {
                    if (updateCheckState != UpdateCheckState.Checking) {
                        updateCheckState = UpdateCheckState.Checking
                        scope.launch { updateCheckState = runUpdateCheck() }
                    }
                },
                onDismissUpdateDialog = { updateCheckState = UpdateCheckState.Idle },
                snackbarHostState = snackbarHostState
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchedFromExternalIntent = intent.isExternalEmailLaunch()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.emailUri() ?: return
        loadEmailFromUri(uri)
    }

    /**
     * Read an email out of [uri] and hand the bytes to the view model.
     *
     * An effect boundary: every failure becomes a domain error on the UI state
     * rather than an exception escaping into the framework.
     */
    private fun loadEmailFromUri(uri: Uri) {
        try {
            if (uri.scheme == "content") {
                takePersistableReadPermission(uri)
            }
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val filename = displayNameOf(uri) ?: DEFAULT_EMAIL_FILENAME
                viewModel.ingestFromUri(bytes, filename, uri.toString())
            }
        } catch (e: Exception) {
            viewModel.setError("Failed to open email: ${e.message}")
        }
    }

    /**
     * Try to persist read access to a content URI. Failure is expected and
     * harmless: many providers grant one-shot access that is still readable for
     * the rest of this call. The URI is deliberately not logged.
     */
    private fun takePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "No persistable read permission; using transient access")
        }
    }

    /** The display name the content provider reports for [uri], if it reports one. */
    private fun displayNameOf(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }

    /**
     * Write the current email into the shareable cache directory and hand it to
     * the system chooser. Effect boundary: failures surface as a domain error.
     */
    private fun shareCurrentEmail(subject: String) {
        val bytes = viewModel.getCurrentEmailBytes() ?: return

        try {
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(shareDir, sharedEmailFilename(subject))
            file.writeBytes(bytes)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share email"))
        } catch (e: Exception) {
            viewModel.setError("Failed to share email: ${e.message}")
        }
    }

    /**
     * Stream every cached email into the archive the user chose.
     *
     * An effect boundary: the picker gives us a `content://` destination, we open
     * it, and every failure becomes an [ExportState] rather than an exception.
     * [EmailViewModel.exportAll] takes ownership of the stream and closes it, so
     * this must not wrap it in `use`.
     */
    private suspend fun exportArchiveTo(
        destination: Uri,
        onProgress: (processed: Int, total: Int) -> Unit
    ): ExportState =
        try {
            val output = contentResolver.openOutputStream(destination)
            if (output == null) {
                ExportState.Failed("Could not open the chosen file")
            } else {
                ExportState.Done(viewModel.exportAll(output, onProgress))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Export failed: ${e.message}")
            ExportState.Failed(e.message ?: "Unknown error")
        }

    /**
     * The single place an update check happens: query GitHub through WARP and,
     * on success only, record the check time that throttles the launch check.
     * Both the silent launch check and the manual Settings check funnel through
     * here, so "a check ran" and "the throttle timestamp advanced" cannot drift
     * apart.
     *
     * Cancellation is rethrown rather than reported as a failed check: a
     * cancelled coroutine is not an outcome of the check.
     */
    private suspend fun runUpdateCheck(): UpdateCheckState =
        try {
            val result = ImageProxyService.getInstance(this)
                .checkForUpdate(BuildConfig.VERSION_NAME)
            preferencesRepository.setLastUpdateCheck(System.currentTimeMillis())
            if (result.updateAvailable) {
                UpdateCheckState.Available(result)
            } else {
                UpdateCheckState.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            UpdateCheckState.Failed(e.message ?: "Update check failed")
        }

    /**
     * Silently check for a newer release at most once per day and, if one
     * exists, surface a snackbar linking to it. Failures are ignored here so a
     * flaky network never disrupts launch.
     */
    private suspend fun maybeAutoCheckForUpdate(snackbarHostState: SnackbarHostState) {
        val lastCheck = preferencesRepository.lastUpdateCheckEpochMillis.first()
        if (System.currentTimeMillis() - lastCheck < UPDATE_CHECK_INTERVAL_MS) return

        when (val outcome = runUpdateCheck()) {
            is UpdateCheckState.Available -> offerUpdate(outcome.result, snackbarHostState)
            else -> Unit
        }
    }

    /** Offer an available release; the "View" action opens the release page. */
    private suspend fun offerUpdate(result: UpdateResult, snackbarHostState: SnackbarHostState) {
        val action = snackbarHostState.showSnackbar(
            message = "Update available: ${result.latestVersion}",
            actionLabel = "View",
            duration = SnackbarDuration.Long
        )
        if (action == SnackbarResult.ActionPerformed && result.releaseUrl.isNotBlank()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl)))
        }
    }
}

/**
 * Progress while the mailbox is being written out.
 *
 * Not dismissible: bytes are already going into a file the user chose, and there
 * is no meaningful "cancel" that leaves a valid archive behind. The count is shown
 * because an unbounded cache can take a while, and a bare spinner is
 * indistinguishable from a hang.
 */
@Composable
private fun ExportProgressDialog(processed: Int, total: Int) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Exporting emails") },
        text = {
            Column {
                Text(
                    text = if (total > 0) "$processed of $total" else "Preparing…",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { processed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { }
    )
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
    history: LazyPagingItems<HistoryEntry>,
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
    onExportAll: () -> Unit,
    preferences: AppPreferences,
    onAlwaysLoadRemoteImagesChange: (Boolean) -> Unit,
    onEnablePrivacyProxyChange: (Boolean) -> Unit,
    onAcceptCloudflareTerms: () -> Unit,
    updateCheckState: UpdateCheckState,
    onCheckForUpdate: () -> Unit,
    onDismissUpdateDialog: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCloudflareTermsDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsSheetState = rememberModalBottomSheetState()
    val searchFocusRequester = remember { FocusRequester() }

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
            if (history.itemCount > 0 || isSearchActive || filterHasAttachments) {
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
                            label = { Text(sortChipLabel(sortField, sortDirection)) },
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
                            SORT_OPTIONS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        onSortChange(option.field, option.direction)
                                        showSortMenu = false
                                    }
                                )
                            }
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
                preferences = preferences,
                onAlwaysLoadRemoteImagesChange = onAlwaysLoadRemoteImagesChange,
                onEnablePrivacyProxyChange = { enabled ->
                    // The consent gate is a decision over values; only the write
                    // is an effect, and it is delegated upward.
                    if (enabled && !preferences.cloudflareTermsAccepted) {
                        showCloudflareTermsDialog = true
                    } else {
                        onEnablePrivacyProxyChange(enabled)
                    }
                },
                onClearCache = {
                    showSettingsSheet = false
                    showClearCacheDialog = true
                },
                onExportAll = {
                    showSettingsSheet = false
                    onExportAll()
                },
                appVersion = BuildConfig.VERSION_NAME,
                onOpenDiagnostics = {
                    showSettingsSheet = false
                    showDiagnostics = true
                },
                onCheckForUpdate = onCheckForUpdate
            )
        }
    }

    // WARP diagnostics dialog
    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }

    // Update check dialogs
    when (val update = updateCheckState) {
        is UpdateCheckState.Available -> UpdateAvailableDialog(
            result = update.result,
            onDismiss = onDismissUpdateDialog
        )
        is UpdateCheckState.UpToDate -> UpToDateDialog(
            currentVersion = update.currentVersion,
            onDismiss = onDismissUpdateDialog
        )
        is UpdateCheckState.Failed -> AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            title = { Text("Update check failed") },
            text = { Text(update.message) },
            confirmButton = {
                TextButton(onClick = onDismissUpdateDialog) { Text("OK") }
            }
        )
        UpdateCheckState.Checking, UpdateCheckState.Idle -> Unit
    }

    // Cloudflare WARP Terms of Service dialog
    if (showCloudflareTermsDialog) {
        CloudflareTermsDialog(
            onAccept = {
                showCloudflareTermsDialog = false
                onAcceptCloudflareTerms()
            },
            onDecline = {
                showCloudflareTermsDialog = false
            }
        )
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

/** One entry in the sort dropdown: a label paired with the order it selects. */
private data class SortOption(
    val label: String,
    val field: SortField,
    val direction: SortDirection
)

/**
 * The offered sort orders, in menu order. Listing them as data rather than as
 * six near-identical menu items keeps the label and the order it applies in one
 * place, so they cannot disagree.
 */
private val SORT_OPTIONS = listOf(
    SortOption("Date (newest first)", SortField.DATE, SortDirection.DESCENDING),
    SortOption("Date (oldest first)", SortField.DATE, SortDirection.ASCENDING),
    SortOption("Subject (A-Z)", SortField.SUBJECT, SortDirection.ASCENDING),
    SortOption("Subject (Z-A)", SortField.SUBJECT, SortDirection.DESCENDING),
    SortOption("Sender (A-Z)", SortField.SENDER, SortDirection.ASCENDING),
    SortOption("Sender (Z-A)", SortField.SENDER, SortDirection.DESCENDING)
)

/** Label for the sort chip: the active field plus an arrow for the direction. */
private fun sortChipLabel(field: SortField, direction: SortDirection): String {
    val name = when (field) {
        SortField.DATE -> "Date"
        SortField.SUBJECT -> "Subject"
        SortField.SENDER -> "Sender"
    }
    val arrow = if (direction == SortDirection.ASCENDING) " ↑" else " ↓"
    return name + arrow
}

/**
 * Dialog for Cloudflare WARP Terms of Service consent.
 *
 * This dialog is shown when the user enables the privacy proxy for the first time.
 * Images are fetched through Cloudflare WARP infrastructure, which requires users
 * to accept Cloudflare's Terms of Service.
 */
@Composable
private fun CloudflareTermsDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Cloudflare WARP Terms") },
        text = {
            Column {
                Text(
                    "The privacy proxy uses Cloudflare WARP to hide your IP address when loading remote images.\n\n" +
                    "By enabling this feature, you agree to Cloudflare's Terms of Service and Privacy Policy.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = {
                        // Open Cloudflare Terms of Service in browser
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.cloudflare.com/application/terms/"))
                        context.startActivity(intent)
                    }
                ) {
                    Text("View Cloudflare Terms of Service")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Accept & Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingsContent(
    cacheStats: CacheStats,
    preferences: AppPreferences,
    onAlwaysLoadRemoteImagesChange: (Boolean) -> Unit,
    onEnablePrivacyProxyChange: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onExportAll: () -> Unit,
    appVersion: String,
    onOpenDiagnostics: () -> Unit,
    onCheckForUpdate: () -> Unit
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
                checked = preferences.alwaysLoadRemoteImages,
                onCheckedChange = onAlwaysLoadRemoteImagesChange,
                modifier = Modifier.testTag("alwaysLoadRemoteImagesSwitch")
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
                    text = when {
                        preferences.enablePrivacyProxy && preferences.cloudflareTermsAccepted ->
                            "Images loaded through Cloudflare WARP to hide your IP"
                        preferences.enablePrivacyProxy ->
                            "Cloudflare terms acceptance required"
                        else ->
                            "Load images through a privacy proxy to hide your IP address"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = preferences.enablePrivacyProxy,
                onCheckedChange = onEnablePrivacyProxyChange,
                modifier = Modifier.testTag("privacyProxySwitch")
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

        // Export all emails
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Export all emails",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Save every cached email into a .zip archive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onExportAll,
                enabled = cacheStats.entryCount > 0,
                modifier = Modifier.testTag("exportAllButton")
            ) {
                Text("Export")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // About & developer section
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Version",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = appVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onCheckForUpdate,
                modifier = Modifier.testTag("checkForUpdatesButton")
            ) {
                Text("Check for updates")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WARP diagnostics",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Inspect the WireGuard tunnel and connection state",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.testTag("openDiagnosticsButton")
            ) {
                Text("Open")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HistoryList(
    entries: LazyPagingItems<HistoryEntry>,
    onEntryClick: (HistoryEntry) -> Unit,
    onEntryDelete: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = "Open an .eml or .msg file to get started."
) {
    // "No results" and "not loaded yet" are different states and must not look
    // the same: an empty first frame is normal while the first page is in
    // flight, so the message waits until loading has actually finished.
    val settled = entries.loadState.refresh is LoadState.NotLoading
    if (entries.itemCount == 0 && settled) {
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
        // One clock reading per refresh. Rows then render against a single
        // instant, so two of them can never straddle a minute boundary and
        // disagree about "now", and scrolling does not re-read the clock per row.
        val now = remember(entries.loadState.refresh) { System.currentTimeMillis() }

        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                count = entries.itemCount,
                key = entries.itemKey { it.id }
            ) { index ->
                // Null only if a page were dropped between count and read.
                // Placeholders are disabled, so this is not the common path.
                entries[index]?.let { entry ->
                    HistoryRow(
                        entry = entry,
                        now = now,
                        onClick = { onEntryClick(entry) },
                        onDelete = { onEntryDelete(entry) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    now: Long,
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
                    text = formatRelativeTimestamp(entry.effectiveDate, now),
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
                        text = sourceLabel(uri),
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

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryList() {
    val sample = PagingData.from(
        listOf(
            HistoryEntry(
                id = 1,
                blobHash = "abc",
                displayName = "Sample Email",
                originalUri = "content://email/1",
                lastAccessed = System.currentTimeMillis()
            )
        )
    )
    LetterboxTheme {
        HistoryList(
            entries = flowOf(sample).collectAsLazyPagingItems(),
            onEntryClick = {},
            onEntryDelete = {}
        )
    }
}
