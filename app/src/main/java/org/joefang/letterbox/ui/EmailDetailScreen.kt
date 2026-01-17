package org.joefang.letterbox.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.ImageFetchResult
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
    var showMenu by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(email.attachments.isNotEmpty()) }
    val context = LocalContext.current
    
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
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Remote images banner
            if (hasRemoteImages && !sessionLoadImages && onShowImages != null) {
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

            // WebView for HTML content
            // When sessionLoadImages is true and useProxy is true, images are loaded 
            // through the WARP privacy proxy. Otherwise, images are blocked by default.
            val processedHtml = email.bodyHtml ?: "<p>No content available</p>"
            
            EmailWebView(
                html = processedHtml,
                getResource = email.getResource,
                allowNetworkLoads = sessionLoadImages,
                useProxy = useProxy,
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
 * - Disables file access for security
 * - Intercepts cid: URLs to load inline images from email attachments
 * - Optionally loads remote images through the WARP privacy proxy
 */
@Composable
private fun EmailWebView(
    html: String,
    getResource: (String) -> ByteArray?,
    modifier: Modifier = Modifier,
    allowNetworkLoads: Boolean = false,
    useProxy: Boolean = true
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Security settings for network access:
                // - When allowNetworkLoads=false: Block all network requests (default secure state)
                // - When allowNetworkLoads=true: Allow network requests to be attempted
                //   The shouldInterceptRequest callback will then either:
                //   - Route through the privacy proxy (when useProxy=true)
                //   - Let WebView handle directly (when useProxy=false)
                //
                // IMPORTANT: We must NOT block network loads when useProxy=true, because
                // shouldInterceptRequest is only called when the WebView attempts to make
                // a request. If blockNetworkLoads=true, no requests are attempted, and
                // the proxy interception never happens.
                //
                // NOTE: Setting blockNetworkLoads=false requires INTERNET permission.
                // If the app doesn't have this permission (privacy-focused design),
                // we catch the SecurityException and keep network loads blocked.
                // The proxy will still work through shouldInterceptRequest for requests
                // that do get through.
                val shouldBlockNetworkAccess = !allowNetworkLoads
                
                settings.apply {
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptEnabled = false // Disable JS for security
                    
                    // Try to set network blocking preferences. This may throw SecurityException
                    // if the app doesn't have INTERNET permission (intentional for privacy).
                    try {
                        blockNetworkLoads = shouldBlockNetworkAccess
                        blockNetworkImage = shouldBlockNetworkAccess
                    } catch (e: SecurityException) {
                        // App doesn't have INTERNET permission (by design for privacy).
                        // Keep network loads blocked - the proxy still intercepts requests
                        // through shouldInterceptRequest.
                        Log.d("EmailWebView", "INTERNET permission not granted, network loads will be blocked")
                        blockNetworkLoads = true
                        blockNetworkImage = true
                    }
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
                                WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    404,
                                    "Not Found",
                                    emptyMap(),
                                    ByteArrayInputStream("Resource not found".toByteArray())
                                )
                            }
                        }

                        // Handle HTTP/HTTPS requests
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            // Only fetch images if allowed
                            if (!allowNetworkLoads) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream("External resources are blocked for security".toByteArray())
                                )
                            }

                            // If not using proxy, let WebView handle directly
                            if (!useProxy) {
                                return null
                            }

                            // Fetch through privacy proxy
                            // Note: shouldInterceptRequest runs on a background thread,
                            // so runBlocking is safe here and won't cause ANRs.
                            // ImageProxyService.getInstance() is a thread-safe singleton.
                            return try {
                                val proxyService = ImageProxyService.getInstance(context)
                                val result = runBlocking {
                                    proxyService.fetchImage(url)
                                }
                                
                                when (result) {
                                    is ImageFetchResult.Success -> {
                                        WebResourceResponse(
                                            result.mimeType,
                                            null,
                                            ByteArrayInputStream(result.data)
                                        )
                                    }
                                    is ImageFetchResult.Error -> {
                                        Log.w("EmailWebView", "Proxy fetch failed for $url: ${result.message}")
                                        WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            502,
                                            "Bad Gateway",
                                            emptyMap(),
                                            ByteArrayInputStream("Failed to fetch image: ${result.message}".toByteArray())
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("EmailWebView", "Proxy error for $url", e)
                                WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    500,
                                    "Internal Error",
                                    emptyMap(),
                                    ByteArrayInputStream("Proxy error: ${e.message}".toByteArray())
                                )
                            }
                        }

                        // For other schemes, return null to let WebView handle them
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
                            showLinkContextMenu(ctx, linkUrl)
                            true
                        }
                        WebView.HitTestResult.IMAGE_TYPE -> {
                            // For images, show image context menu
                            val imageUrl = hitTestResult.extra ?: return@setOnLongClickListener false
                            showImageContextMenu(ctx, imageUrl)
                            true
                        }
                        else -> false
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
        modifier = modifier
    )
}

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
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
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
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse(mailtoUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w("EmailWebView", "Failed to open mailto: $mailtoUrl", e)
    }
}

/**
 * Copy text to the clipboard.
 */
private fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * Show a context menu for links with options to open or copy.
 * 
 * This provides conventional UX for long-pressing links:
 * - Open link in browser
 * - Copy link to clipboard
 */
private fun showLinkContextMenu(context: Context, url: String) {
    val items = arrayOf("Open link", "Copy link address")
    
    android.app.AlertDialog.Builder(context)
        .setTitle("Link options")
        .setItems(items) { _, which ->
            when (which) {
                0 -> openUrlInBrowser(context, url)
                1 -> copyToClipboard(context, "Link URL", url, "Link copied")
            }
        }
        .show()
}

/**
 * Show a context menu for images with options to open or copy URL.
 * 
 * This provides conventional UX for long-pressing images:
 * - Open image in browser
 * - Copy image URL to clipboard
 */
private fun showImageContextMenu(context: Context, imageUrl: String) {
    val items = arrayOf("Open image", "Copy image URL")
    
    android.app.AlertDialog.Builder(context)
        .setTitle("Image options")
        .setItems(items) { _, which ->
            when (which) {
                0 -> openUrlInBrowser(context, imageUrl)
                1 -> copyToClipboard(context, "Image URL", imageUrl, "Image URL copied")
            }
        }
        .show()
}
