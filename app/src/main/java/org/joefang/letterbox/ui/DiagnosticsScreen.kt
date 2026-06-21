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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.joefang.letterbox.data.ImageProxyService
import org.joefang.letterbox.ffi.proxy.WarpDiagnostics
import org.joefang.letterbox.ffi.proxy.WarpStoredConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Loading state for the persisted WARP configuration.
 *
 * This is read straight from disk and never touches the network, so it resolves
 * even when the tunnel itself cannot connect — making it the anchor of the
 * debug screen when something is wrong.
 */
private sealed interface StoredState {
    data object Loading : StoredState
    data class Loaded(val config: WarpStoredConfig) : StoredState
    data class Failed(val message: String) : StoredState
}

/**
 * Loading state for the live tunnel handshake/diagnostics.
 *
 * Resolving this forces the tunnel to provision and handshake, so it doubles as
 * a connectivity self-test and may legitimately fail while [StoredState] still
 * succeeds.
 */
private sealed interface LiveState {
    data object Loading : LiveState
    data class Loaded(val diagnostics: WarpDiagnostics) : LiveState
    data class Failed(val message: String) : LiveState
}

/**
 * Developer dialog for inspecting and repairing the WireGuard/WARP tunnel.
 *
 * It surfaces two layers independently:
 *  - the persisted identity and configuration (always available), and
 *  - the live tunnel session (which may be down).
 *
 * It also offers a one-tap identity reset that regenerates the keypair and
 * re-registers with Cloudflare. The private key is viewable behind an explicit
 * reveal toggle.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableIntStateOf(0) }
    var storedState by remember { mutableStateOf<StoredState>(StoredState.Loading) }
    var liveState by remember { mutableStateOf<LiveState>(LiveState.Loading) }
    var revealPrivateKey by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        revealPrivateKey = false
        storedState = StoredState.Loading
        liveState = LiveState.Loading
        val service = ImageProxyService.getInstance(context)

        storedState = try {
            StoredState.Loaded(service.getStoredConfig())
        } catch (e: Exception) {
            StoredState.Failed(e.message ?: "Failed to read stored configuration")
        }

        liveState = try {
            LiveState.Loaded(service.getDiagnostics())
        } catch (e: Exception) {
            LiveState.Failed(e.message ?: "Failed to establish the tunnel")
        }
    }

    if (confirmReset) {
        ResetConfirmationDialog(
            onConfirm = {
                confirmReset = false
                resetError = null
                resetting = true
                scope.launch {
                    resetError = try {
                        ImageProxyService.getInstance(context).resetIdentity()
                        null
                    } catch (e: Exception) {
                        e.message ?: "Reset failed"
                    }
                    resetting = false
                    reloadKey++
                }
            },
            onDismiss = { confirmReset = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WARP diagnostics") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (resetting) {
                    CenteredProgress("Refreshing WARP identity…")
                    return@Column
                }
                resetError?.let { message ->
                    DiagnosticRow(
                        "Reset failed",
                        message,
                        valueColor = MaterialTheme.colorScheme.error
                    )
                }

                StoredConfigSection(
                    state = storedState,
                    revealPrivateKey = revealPrivateKey,
                    onToggleReveal = { revealPrivateKey = !revealPrivateKey }
                )

                LiveTunnelSection(state = liveState)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row {
                val stored = storedState as? StoredState.Loaded
                val live = liveState as? LiveState.Loaded
                if (stored != null) {
                    TextButton(onClick = {
                        copyToClipboard(
                            context,
                            "WARP diagnostics",
                            formatForClipboard(stored.config, live?.diagnostics)
                        )
                    }) { Text("Copy") }
                }
                TextButton(
                    enabled = !resetting,
                    onClick = { confirmReset = true }
                ) { Text("Reset") }
                TextButton(
                    enabled = !resetting,
                    onClick = { reloadKey++ }
                ) { Text("Refresh") }
            }
        }
    )
}

@Composable
private fun ResetConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset WARP identity?") },
        text = {
            Text(
                "This generates a brand-new WireGuard keypair and re-registers " +
                    "with Cloudflare, replacing the stored identity. The current " +
                    "device registration is deleted and the tunnel reconnects. " +
                    "Use this if the connection is stuck."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StoredConfigSection(
    state: StoredState,
    revealPrivateKey: Boolean,
    onToggleReveal: () -> Unit
) {
    SectionLabel("Stored configuration")
    when (state) {
        is StoredState.Loading -> CenteredProgress("Reading stored configuration…")
        is StoredState.Failed -> DiagnosticRow(
            "Error",
            state.message,
            valueColor = MaterialTheme.colorScheme.error
        )
        is StoredState.Loaded -> StoredConfigBody(
            config = state.config,
            revealPrivateKey = revealPrivateKey,
            onToggleReveal = onToggleReveal
        )
    }
}

@Composable
private fun StoredConfigBody(
    config: WarpStoredConfig,
    revealPrivateKey: Boolean,
    onToggleReveal: () -> Unit
) {
    if (!config.hasConfig) {
        DiagnosticRow(
            "Provisioned",
            "No — WARP has not been registered yet",
            valueColor = MaterialTheme.colorScheme.error
        )
        DiagnosticRow("Config file", config.configFilePath, monospace = true)
        return
    }

    DiagnosticRow("Tunnel", if (config.tunnelActive) "Active" else "Not running")
    DiagnosticRow("WARP enabled", if (config.warpEnabled) "Yes" else "No")
    DiagnosticRow("Account type", config.accountType.ifBlank { "unknown" })
    DiagnosticRow("Account ID", config.accountId.ifBlank { "—" }, monospace = true)
    DiagnosticRow("License key", config.licenseKey.ifBlank { "—" }, monospace = true)
    DiagnosticRow("Last provisioned", formatTimestamp(config.lastUpdatedSecs))

    SectionLabel("Endpoint")
    DiagnosticRow("Host", config.endpointHost.ifBlank { "—" })
    DiagnosticRow("IPv4", "${config.endpointIpv4}:${config.endpointPort}")

    SectionLabel("Local address")
    DiagnosticRow("IPv4", config.localAddressIpv4.ifBlank { "—" })

    SectionLabel("Keys")
    DiagnosticRow("Public key", config.publicKey.ifBlank { "—" }, monospace = true)
    DiagnosticRow("Peer public key", config.peerPublicKey.ifBlank { "—" }, monospace = true)
    DiagnosticRow(
        "Private key",
        if (revealPrivateKey) config.privateKey else "•••••••• (tap reveal)",
        monospace = true
    )
    TextButton(onClick = onToggleReveal) {
        Text(if (revealPrivateKey) "Hide private key" else "Reveal private key")
    }

    SectionLabel("Storage")
    DiagnosticRow("Config file", config.configFilePath, monospace = true)
}

@Composable
private fun LiveTunnelSection(state: LiveState) {
    SectionLabel("Live tunnel")
    when (state) {
        is LiveState.Loading -> CenteredProgress("Establishing tunnel…")
        is LiveState.Failed -> {
            DiagnosticRow(
                "Connection",
                "Failed",
                valueColor = MaterialTheme.colorScheme.error
            )
            DiagnosticRow(
                "Reason",
                state.message,
                valueColor = MaterialTheme.colorScheme.error
            )
        }
        is LiveState.Loaded -> LiveTunnelBody(state.diagnostics)
    }
}

@Composable
private fun LiveTunnelBody(d: WarpDiagnostics) {
    val connected = d.connectionState == "connected"
    DiagnosticRow(
        "Connection",
        if (connected) "Connected" else "Disconnected",
        valueColor = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    DiagnosticRow(
        "Last handshake",
        d.lastHandshakeSecs?.let { "${it}s ago" } ?: "never"
    )
    DiagnosticRow("Sent", formatBytes(d.txBytes))
    DiagnosticRow("Received", formatBytes(d.rxBytes))
    DiagnosticRow("Est. loss", "%.1f%%".format(d.estimatedLoss * 100f))
    DiagnosticRow("Est. RTT", d.rttMs?.let { "$it ms" } ?: "—")
}

@Composable
private fun CenteredProgress(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    valueColor: Color = MaterialTheme.colorScheme.onSurface
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

private fun formatTimestamp(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "never"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    return formatter.format(Date(epochSeconds * 1000L))
}

private fun formatForClipboard(config: WarpStoredConfig, live: WarpDiagnostics?): String = buildString {
    appendLine("# Stored configuration")
    appendLine("has_config=${config.hasConfig}")
    appendLine("tunnel_active=${config.tunnelActive}")
    appendLine("warp_enabled=${config.warpEnabled}")
    appendLine("account_type=${config.accountType}")
    appendLine("account_id=${config.accountId}")
    appendLine("license_key=${config.licenseKey}")
    appendLine("last_provisioned=${formatTimestamp(config.lastUpdatedSecs)}")
    appendLine("endpoint_host=${config.endpointHost}")
    appendLine("endpoint_ipv4=${config.endpointIpv4}")
    appendLine("endpoint_port=${config.endpointPort}")
    appendLine("local_address_ipv4=${config.localAddressIpv4}")
    appendLine("public_key=${config.publicKey}")
    appendLine("peer_public_key=${config.peerPublicKey}")
    appendLine("private_key=${config.privateKey}")
    appendLine("config_file=${config.configFilePath}")
    appendLine()
    appendLine("# Live tunnel")
    if (live == null) {
        appendLine("status=unavailable")
        return@buildString
    }
    appendLine("connection_state=${live.connectionState}")
    appendLine("last_handshake_secs=${live.lastHandshakeSecs ?: "never"}")
    appendLine("tx_bytes=${live.txBytes}")
    appendLine("rx_bytes=${live.rxBytes}")
    appendLine("estimated_loss=${live.estimatedLoss}")
    appendLine("rtt_ms=${live.rttMs ?: "n/a"}")
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
