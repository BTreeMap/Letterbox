package com.btreemap.letterbox.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Data class representing parsed email content for display.
 */
data class EmailContent(
    val subject: String,
    val from: String,
    val to: String,
    val date: String,
    val bodyHtml: String?,
    val getResource: (String) -> ByteArray?
)

/**
 * Email detail screen that displays the email content using a secure WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailDetailScreen(
    email: EmailContent,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = email.subject, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
            // Header section
            EmailHeader(
                from = email.from,
                to = email.to,
                date = email.date,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // WebView for HTML content
            EmailWebView(
                html = email.bodyHtml ?: "<p>No content available</p>",
                getResource = email.getResource,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun EmailHeader(
    from: String,
    to: String,
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
 */
@Composable
private fun EmailWebView(
    html: String,
    getResource: (String) -> ByteArray?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // Security settings
                settings.apply {
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptEnabled = false // Disable JS for security
                    blockNetworkLoads = true // Block all network requests
                    blockNetworkImage = true
                }

                // Custom WebViewClient to intercept cid: URLs
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

                        // Block all other external requests with a clear error
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Forbidden",
                            emptyMap(),
                            ByteArrayInputStream("External resources are blocked for security".toByteArray())
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // Block all navigation for security
                        return true
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
