package org.joefang.letterbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.ResourceFetchResult
import org.joefang.letterbox.data.ImageProxyService
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Attachment metadata for display.
 */
data class AttachmentData(
    val name: String,
    val contentType: String,
    val size: Long,
    val index: Int
)

/**
 * Data class representing parsed email content for display.
 */
data class EmailContent(
    val subject: String,
    val from: String,
    val to: String,
    val cc: String = "",
    val replyTo: String = "",
    val messageId: String = "",
    val date: String,
    val bodyHtml: String?,
    val attachments: List<AttachmentData> = emptyList(),
    val getResource: (String) -> ByteArray?,
    val getAttachmentContent: (Int) -> ByteArray? = { null }
)

/**
 * Email detail screen that displays the email content using a secure WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailDetailScreen(
    email: EmailContent,
    onNavigateBack: () -> Unit,
    onRemoveFromHistory: (() -> Unit)? = null,
    onShareEml: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasRemoteImages: Boolean = false,
    sessionLoadImages: Boolean = false,
    onShowImages: (() -> Unit)? = null,
    useProxy: Boolean = true
) {
    // Derived once; the banner and the network gate below must not re-derive it
    // independently, which is exactly how they came to disagree before.
    val remoteImagePolicy = RemoteImagePolicy.of(hasRemoteImages, sessionLoadImages)

    var showMenu by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(email.attachments.isNotEmpty()) }
    var linkSheetState by remember { mutableStateOf<LinkSheetState?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    // Handle system back button/gesture
    BackHandler(onBack = onNavigateBack)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = email.subject, 
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("topBarTitle")
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
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
                        if (onShareEml != null) {
                            DropdownMenuItem(
                                text = { Text("Share .eml") },
                                onClick = {
                                    showMenu = false
                                    onShareEml()
                                }
                            )
                        }
                        if (onRemoveFromHistory != null) {
                            DropdownMenuItem(
                                text = { Text("Remove from history") },
                                onClick = {
                                    showMenu = false
                                    onRemoveFromHistory()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Details") },
                            onClick = {
                                showMenu = false
                                showDetailsDialog = true
                            }
                        )
                    }
                }
            )
        },
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Remote images banner
            if (remoteImagePolicy.showsBanner && onShowImages != null) {
                RemoteImagesBanner(
                    onShowImages = onShowImages,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Header section
            EmailHeader(
                from = email.from,
                to = email.to,
                cc = email.cc,
                date = email.date,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Attachments section (collapsible)
            if (email.attachments.isNotEmpty()) {
                AttachmentsSection(
                    attachments = email.attachments,
                    expanded = showAttachments,
                    onToggleExpanded = { showAttachments = !showAttachments },
                    onAttachmentClick = { attachment ->
                        openAttachment(context, attachment, email.getAttachmentContent)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // WebView for HTML content. The gate and the banner above read the
            // same [RemoteImagePolicy]; see that type for why they must.
            val processedHtml = email.bodyHtml ?: "<p>No content available</p>"

            EmailWebView(
                html = processedHtml,
                getResource = email.getResource,
                allowNetworkLoads = remoteImagePolicy.allowsNetworkLoads,
                useProxy = useProxy,
                onLinkLongPress = { rawUrl ->
                    val trimmedRaw = rawUrl.trim()
                    linkSheetState = LinkResolver.resolve(rawUrl).let { resolution ->
                        val displayUrl = if (resolution.fixedUrl.isBlank()) trimmedRaw else resolution.fixedUrl
                        LinkSheetState(
                            fixedUrl = displayUrl,
                            openAllowed = resolution.openAllowed,
                            openUri = resolution.openUri
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }
    
    // Details dialog with extended information
    if (showDetailsDialog) {
        EmailDetailsDialog(
            email = email,
            onDismiss = { showDetailsDialog = false }
        )
    }

    linkSheetState?.let { sheetState ->
        LinkActionSheet(
            state = sheetState,
            onOpen = { uri ->
                openUrlInCustomTabs(context, uri)
            },
            onCopy = { url ->
                copyLinkToClipboard(context, snackbarHostState, coroutineScope, url)
            },
            onDismiss = { linkSheetState = null }
        )
    }
}

@Composable
private fun EmailDetailsDialog(
    email: EmailContent,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Email Details") },
        text = { 
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                DetailRow("Subject", email.subject, valueTestTag = "dialogSubject")
                DetailRow("From", email.from)
                DetailRow("To", email.to)
                if (email.cc.isNotBlank()) {
                    DetailRow("Cc", email.cc)
                }
                if (email.replyTo.isNotBlank()) {
                    DetailRow("Reply-To", email.replyTo)
                }
                DetailRow("Date", email.date)
                if (email.messageId.isNotBlank()) {
                    DetailRow("Message-ID", email.messageId)
                }
                
                // Attachments summary
                if (email.attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Attachments (${email.attachments.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("dialogAttachmentsCount")
                    )
                    email.attachments.forEach { attachment ->
                        Text(
                            text = "• ${attachment.name} (${formatFileSize(attachment.size)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, valueTestTag: String? = null) {
    if (value.isNotBlank()) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier
            )
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<AttachmentData>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAttachmentClick: (AttachmentData) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column {
            // Header row with toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📎",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Attachments (${attachments.size})",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            
            // Attachment list
            if (expanded) {
                HorizontalDivider()
                attachments.forEach { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        onClick = { onAttachmentClick(attachment) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentData,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${attachment.contentType} • ${formatFileSize(attachment.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

private fun openAttachment(
    context: Context,
    attachment: AttachmentData,
    getContent: (Int) -> ByteArray?
) {
    val content = getContent(attachment.index) ?: return
    
    try {
        // Save attachment to cache directory
        val cacheDir = File(context.cacheDir, "attachments")
        cacheDir.mkdirs()
        
        // Sanitize filename to prevent path traversal attacks
        val safeFilename = sanitizeFilename(attachment.name)
        val file = File(cacheDir, safeFilename)
        
        // Verify the file is actually within the cache directory
        if (!file.canonicalPath.startsWith(cacheDir.canonicalPath)) {
            throw SecurityException("Invalid attachment filename")
        }
        
        file.writeBytes(content)
        
        // Create content URI via FileProvider
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        // Open with default app
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.contentType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: SecurityException) {
        android.widget.Toast.makeText(
            context,
            "Cannot open attachment: invalid filename",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open attachment: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Sanitize filename to prevent path traversal attacks.
 * Removes path separators and other dangerous characters.
 */
private fun sanitizeFilename(name: String): String {
    // Remove path separators and null bytes
    val sanitized = name
        .replace("/", "_")
        .replace("\\", "_")
        .replace("\u0000", "")
        .trim()
    
    // If filename is empty or just dots, use a default name
    return if (sanitized.isBlank() || sanitized.all { it == '.' }) {
        "attachment"
    } else {
        sanitized
    }
}

@Composable
private fun RemoteImagesBanner(
    onShowImages: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Remote images are hidden",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Images will be loaded through a privacy proxy to protect your IP address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onShowImages) {
                Text("Show")
            }
        }
    }
}

@Composable
private fun EmailHeader(
    from: String,
    to: String,
    cc: String,
    date: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (from.isNotBlank()) {
            Text(
                text = "From: $from",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (to.isNotBlank()) {
            Text(
                text = "To: $to",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (cc.isNotBlank()) {
            Text(
                text = "Cc: $cc",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (date.isNotBlank()) {
            Text(
                text = "Date: $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Secure WebView that only loads content we provide.
 * - Disables file access and scripting for security
 * - Intercepts cid: URLs to load inline images from email attachments
 * - Optionally loads remote subresources through the WARP privacy proxy
 *
 * ## What the remote-content policy is about
 *
 * Contacting a third party, not fetching a picture. A remote stylesheet or web
 * font tells its host that this message was opened, on this network, at this
 * moment, exactly as a tracking pixel does — so [allowNetworkLoads] gates every
 * external subresource alike, and content already inside the message (`cid:`
 * attachments, `data:` URIs) is never gated at all, because there is nobody for
 * it to tell.
 *
 * Scripting is off and stays off, and the proxy independently refuses to hand
 * back executable content, so "no code runs from a message" does not rest on the
 * renderer alone.
 *
 * ## Why the policy is read through [rememberUpdatedState]
 *
 * `AndroidView`'s `factory` runs **once**, for the life of the view. A
 * `WebViewClient` built there captures whatever `allowNetworkLoads` held at
 * first composition and keeps it for ever, so tapping "Show images" flipped the
 * policy, dismissed the banner, and changed nothing: the interceptor went on
 * answering 403 and `settings.blockNetworkLoads` stayed shut, which is why no
 * request ever reached the proxy.
 *
 * Reading the current value through a `State` instead means the client and the
 * settings both follow recomposition, which is the only place the policy can
 * change.
 */
@Composable
private fun EmailWebView(
    html: String,
    getResource: (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    allowNetworkLoads: Boolean = false,
    useProxy: Boolean = true,
    onLinkLongPress: (String) -> Unit
) {
    val context = LocalContext.current

    // Read by the interceptor below, which outlives this composition.
    val currentAllowNetworkLoads by rememberUpdatedState(allowNetworkLoads)
    val currentUseProxy by rememberUpdatedState(useProxy)

    // What the view was last told to render. A WebView that has already laid out
    // a page with images blocked will not retry them on its own, so a change of
    // policy has to reload just as a change of content does — and neither should
    // happen on an unrelated recomposition, which would throw away scroll
    // position mid-read.
    //
    // Deliberately *not* snapshot state: `update` both reads and writes this, and
    // an observable value would invalidate the block that wrote it.
    val lastLoaded = remember { arrayOfNulls<Any>(1) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Constant for the life of the view. The network-blocking
                // settings are *not* constant and are applied in `update`
                // below, because this factory never runs again.
                settings.apply {
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptEnabled = false // Disable JS for security
                }

                // Custom WebViewClient to intercept URLs
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null

                        // Intercept cid: URLs for inline images
                        if (url.startsWith("cid:")) {
                            val cid = url.removePrefix("cid:")
                            val bytes = getResource(cid)
                            return if (bytes != null) {
                                val mimeType = guessMimeType(cid, bytes)
                                WebResourceResponse(
                                    mimeType,
                                    "utf-8",
                                    ByteArrayInputStream(bytes)
                                )
                            } else {
                                // Return 404 for missing cid: resources
                                blocked(404, "Not Found")
                            }
                        }

                        // Handle HTTP/HTTPS requests
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            // The consent gate, and the only one. It covers every
                            // external subresource rather than images alone: a web
                            // font and a stylesheet each contact a third party and
                            // report the message was opened just as a picture does,
                            // so a policy that let them through while blocking
                            // images would leak exactly what it set out to protect.
                            if (!currentAllowNetworkLoads) {
                                return blocked(403, "Forbidden")
                            }

                            // If not using proxy, let WebView handle directly
                            if (!currentUseProxy) {
                                return null
                            }

                            // Fetch through privacy proxy
                            // Note: shouldInterceptRequest runs on a background thread,
                            // so runBlocking is safe here and won't cause ANRs.
                            // ImageProxyService.getInstance() is a thread-safe singleton.
                            return try {
                                val proxyService = ImageProxyService.getInstance(context)
                                // The renderer already said what it wants; asking on
                                // its behalf for something else is what made every
                                // stylesheet fail as "expected image, got text/css".
                                val accept = request?.requestHeaders
                                    ?.entries
                                    ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                                    ?.value
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "*/*"
                                val result = runBlocking {
                                    proxyService.fetchSubresource(url, accept)
                                }

                                when (result) {
                                    is ResourceFetchResult.Success -> {
                                        WebResourceResponse(
                                            result.mimeType,
                                            null,
                                            ByteArrayInputStream(result.data)
                                        )
                                    }
                                    is ResourceFetchResult.Error -> {
                                        Log.w("EmailWebView", "Proxy fetch failed for $url: ${result.message}")
                                        blocked(502, "Bad Gateway")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("EmailWebView", "Proxy error for $url", e)
                                blocked(500, "Internal Error")
                            }
                        }

                        // For other schemes — `data:` above all — return null and
                        // let the WebView handle them. A base64 image is already in
                        // the message: it contacts nobody, so there is nothing for
                        // the remote-content policy to decide about it.
                        return null
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        
                        // Open HTTP/HTTPS links in external browser
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            openUrlInBrowser(ctx, url)
                            return true
                        }
                        
                        // Open mailto: links in email client
                        if (url.startsWith("mailto:")) {
                            openMailtoLink(ctx, url)
                            return true
                        }
                        
                        // Block all other navigation for security
                        return true
                    }
                }
                
                // Enable long-click for link context menu (copy URL, open in browser)
                setOnLongClickListener { v ->
                    val hitTestResult = (v as WebView).hitTestResult
                    when (hitTestResult.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            val linkUrl = hitTestResult.extra ?: return@setOnLongClickListener false
                            if (linkUrl.isBlank()) {
                                false
                            } else {
                                onLinkLongPress(linkUrl)
                                true
                            }
                        }
                        WebView.HitTestResult.IMAGE_TYPE -> false
                        else -> false
                    }
                }
            }
        },
        update = { webView ->
            // Applied on every recomposition, so the gate tracks the policy.
            // `blockNetworkLoads` is scoped to what its name says — resources
            // reached over http/https — and is a second lock behind the
            // interceptor, at the network layer rather than the callback.
            //
            // `blockNetworkImage` is *not* its narrower sibling and is gone. The
            // public API documents it as covering "images specified using
            // network URI schemes", but the WebView glue implements it as
            // `AwSettings.setImagesEnabled(!flag)` — the global image switch,
            // which Android's own documentation describes as controlling "all
            // images, including those embedded using the data URI scheme". So
            // every message whose pictures were inline — a base64 `data:` URI,
            // or a `cid:` attachment this very client serves out of the message
            // itself — rendered blank whenever remote content was blocked, which
            // is the default. Nothing was fetched, nothing failed, and no
            // diagnostic appeared anywhere, because no request was ever made.
            //
            // The name promised a predicate about the network; the
            // implementation applied a predicate about images. Trusting the name
            // hid a bug affecting messages that never touch the network at all.
            webView.settings.blockNetworkLoads = !allowNetworkLoads

            val key = html to allowNetworkLoads
            if (lastLoaded[0] != key) {
                lastLoaded[0] = key
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier
    )
}

/**
 * The response for a subresource this app will not supply.
 *
 * The body is deliberately empty. It used to carry an explanatory sentence,
 * which was harmless for an `<img>` and actively wrong for everything else:
 * handed to the CSS parser as a stylesheet, "External resources are blocked for
 * security" is a syntax error, and the prose was never visible to anyone in
 * either case.
 */
private fun blocked(status: Int, reason: String) = WebResourceResponse(
    "text/plain",
    "utf-8",
    status,
    reason,
    emptyMap(),
    ByteArrayInputStream(ByteArray(0))
)

/**
 * Guess MIME type based on Content-ID extension or file magic bytes.
 */
private fun guessMimeType(cid: String, bytes: ByteArray): String {
    // Check extension first
    val extension = cid.substringAfterLast('.', "").lowercase()
    val mimeFromExtension = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> null
    }
    if (mimeFromExtension != null) return mimeFromExtension

    // Check magic bytes
    if (bytes.size >= 2) {
        // JPEG
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        // PNG
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        // GIF
        if (bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte()
        ) {
            return "image/gif"
        }
    }

    return "application/octet-stream"
}

/**
 * Open a URL in the default browser.
 */
private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("EmailWebView", "Failed to open URL: $url", e)
    }
}

/**
 * Open a mailto: link in the default email client.
 */
private fun openMailtoLink(context: Context, mailtoUrl: String) {
    try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("EmailWebView", "Failed to open mailto: $mailtoUrl", e)
    }
}

private data class LinkSheetState(
    val fixedUrl: String,
    val openAllowed: Boolean,
    val openUri: Uri?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkActionSheet(
    state: LinkSheetState,
    onOpen: (Uri) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Link",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(
                    text = state.fixedUrl,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.openAllowed && state.openUri != null) {
                    Button(
                        onClick = {
                            onOpen(state.openUri)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Open")
                    }
                }
                Button(
                    onClick = {
                        onCopy(state.fixedUrl)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("Copy")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

private fun copyLinkToClipboard(
    context: Context,
    snackbarHostState: SnackbarHostState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    url: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Link", url)
    clipboard.setPrimaryClip(clip)
    coroutineScope.launch {
        snackbarHostState.showSnackbar("Link copied")
    }
}

private fun openUrlInCustomTabs(context: Context, uri: Uri) {
    val customTabsIntent = CustomTabsIntent.Builder().build()
    try {
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTabsIntent.launchUrl(context, uri)
    } catch (e: Exception) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open link").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/**
 * Show a context menu for links with options to open or copy.
 * 
 * This provides conventional UX for long-pressing links:
 * - Open link in browser
 * - Copy link to clipboard
 */
