package org.joefang.letterbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.joefang.letterbox.data.ImageProxyService
import org.joefang.letterbox.ffi.proxy.WarpDiagnostics

/**
 * Loading state for the WireGuard/WARP diagnostics dialog.
 */
private sealed interface DiagnosticsState {
    data object Loading : DiagnosticsState
    data class Loaded(val diagnostics: WarpDiagnostics) : DiagnosticsState
    data class Failed(val message: String) : DiagnosticsState
}

/**
 * Developer dialog that displays the full WireGuard/WARP tunnel state.
 *
 * Fetching diagnostics forces the tunnel to provision and handshake, so this
 * doubles as a connectivity self-test. The private key is hidden until the user
 * explicitly reveals it.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DiagnosticsState>(DiagnosticsState.Loading) }
    var revealPrivateKey by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        state = DiagnosticsState.Loading
        revealPrivateKey = false
        state = try {
            DiagnosticsState.Loaded(ImageProxyService.getInstance(context).getDiagnostics())
        } catch (e: Exception) {
            DiagnosticsState.Failed(e.message ?: "Failed to load diagnostics")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WARP diagnostics") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (val current = state) {
                    is DiagnosticsState.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = "Establishing tunnel…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is DiagnosticsState.Failed -> {
                        Text(
                            text = "Could not establish the tunnel:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    is DiagnosticsState.Loaded -> DiagnosticsBody(
                        diagnostics = current.diagnostics,
                        revealPrivateKey = revealPrivateKey,
                        onToggleReveal = { revealPrivateKey = !revealPrivateKey }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row {
                val loaded = state as? DiagnosticsState.Loaded
                if (loaded != null) {
                    TextButton(onClick = {
                        copyToClipboard(context, "WARP diagnostics", formatDiagnostics(loaded.diagnostics))
                    }) { Text("Copy") }
                }
                TextButton(onClick = { reloadKey++ }) { Text("Refresh") }
            }
        }
    )
}

@Composable
private fun DiagnosticsBody(
    diagnostics: WarpDiagnostics,
    revealPrivateKey: Boolean,
    onToggleReveal: () -> Unit
) {
    val connected = diagnostics.connectionState == "connected"
    DiagnosticRow(
        "Connection",
        if (connected) "Connected" else "Disconnected",
        valueColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    DiagnosticRow("WARP enabled", if (diagnostics.warpEnabled) "Yes" else "No")
    DiagnosticRow("Account type", diagnostics.accountType.ifBlank { "unknown" })
    DiagnosticRow("Account ID", diagnostics.accountId.ifBlank { "—" })

    SectionLabel("Endpoint")
    DiagnosticRow("Host", diagnostics.endpointHost)
    DiagnosticRow("IPv4", "${diagnostics.endpointIpv4}:${diagnostics.endpointPort}")

    SectionLabel("Local address")
    DiagnosticRow("IPv4", diagnostics.localAddressIpv4)

    SectionLabel("Session")
    DiagnosticRow(
        "Last handshake",
        diagnostics.lastHandshakeSecs?.let { "${it}s ago" } ?: "never"
    )
    DiagnosticRow("Sent", formatBytes(diagnostics.txBytes))
    DiagnosticRow("Received", formatBytes(diagnostics.rxBytes))
    DiagnosticRow("Est. loss", "%.1f%%".format(diagnostics.estimatedLoss * 100f))
    DiagnosticRow("Est. RTT", diagnostics.rttMs?.let { "${it} ms" } ?: "—")

    SectionLabel("Keys")
    DiagnosticRow("Public key", diagnostics.publicKey, monospace = true)
    DiagnosticRow("Peer public key", diagnostics.peerPublicKey, monospace = true)
    DiagnosticRow(
        "Private key",
        if (revealPrivateKey) diagnostics.privateKey else "•••••••• (tap reveal)",
        monospace = true
    )
    TextButton(onClick = onToggleReveal) {
        Text(if (revealPrivateKey) "Hide private key" else "Reveal private key")
    }
}

@Composable
private fun SectionLabel(label: String) {
    Spacer(Modifier.width(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

private fun formatBytes(bytes: ULong): String {
    val value = bytes.toDouble()
    return when {
        value < 1024 -> "$bytes B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024 * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
        else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDiagnostics(d: WarpDiagnostics): String = buildString {
    appendLine("connection_state=${d.connectionState}")
    appendLine("warp_enabled=${d.warpEnabled}")
    appendLine("account_type=${d.accountType}")
    appendLine("account_id=${d.accountId}")
    appendLine("endpoint_host=${d.endpointHost}")
    appendLine("endpoint_ipv4=${d.endpointIpv4}")
    appendLine("endpoint_port=${d.endpointPort}")
    appendLine("local_address_ipv4=${d.localAddressIpv4}")
    appendLine("last_handshake_secs=${d.lastHandshakeSecs ?: "never"}")
    appendLine("tx_bytes=${d.txBytes}")
    appendLine("rx_bytes=${d.rxBytes}")
    appendLine("estimated_loss=${d.estimatedLoss}")
    appendLine("rtt_ms=${d.rttMs ?: "n/a"}")
    appendLine("public_key=${d.publicKey}")
    appendLine("peer_public_key=${d.peerPublicKey}")
    appendLine("private_key=${d.privateKey}")
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
