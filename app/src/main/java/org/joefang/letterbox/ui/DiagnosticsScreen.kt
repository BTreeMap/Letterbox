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
import org.joefang.letterbox.ffi.proxy.TunnelVerification
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
 * State of the end-to-end check.
 *
 * [Idle] rather than a nullable result, so "never run" is a state the UI names
 * instead of a blank that could be mistaken for a check that found nothing.
 */
private sealed interface VerifyState {
    data object Idle : VerifyState
    data object Running : VerifyState
    data class Done(val result: TunnelVerification) : VerifyState
    data class Failed(val message: String) : VerifyState
}

/**
 * Developer dialog for inspecting and repairing the WARP tunnel.
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
    var revealSecrets by remember { mutableStateOf(false) }
    var verifyState by remember { mutableStateOf<VerifyState>(VerifyState.Idle) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        revealSecrets = false
        // A previous run describes a tunnel that may no longer exist; keeping it
        // on screen after a refresh or reset would be reporting a stale pass.
        verifyState = VerifyState.Idle
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
                    revealSecrets = revealSecrets,
                    onToggleReveal = { revealSecrets = !revealSecrets }
                )

                LiveTunnelSection(state = liveState)

                EndToEndSection(
                    state = verifyState,
                    enabled = liveState is LiveState.Loaded,
                    onRun = {
                        verifyState = VerifyState.Running
                        scope.launch {
                            verifyState = try {
                                VerifyState.Done(
                                    ImageProxyService.getInstance(context).verifyTunnel()
                                )
                            } catch (e: Exception) {
                                VerifyState.Failed(e.message ?: "Verification failed")
                            }
                            // The check moves real bytes, so the counters above
                            // are now stale; refresh them rather than leave two
                            // numbers on screen that disagree.
                            liveState = try {
                                LiveState.Loaded(
                                    ImageProxyService.getInstance(context).getDiagnostics()
                                )
                            } catch (e: Exception) {
                                LiveState.Failed(e.message ?: "Failed to re-read the tunnel")
                            }
                        }
                    }
                )
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
                            formatForClipboard(
                                stored.config,
                                live?.diagnostics,
                                includeSecrets = revealSecrets
                            )
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
                "This generates a brand-new device identity and re-registers " +
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
    revealSecrets: Boolean,
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
            revealSecrets = revealSecrets,
            onToggleReveal = onToggleReveal
        )
    }
}

@Composable
private fun StoredConfigBody(
    config: WarpStoredConfig,
    revealSecrets: Boolean,
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
    DiagnosticRow("Last provisioned", formatTimestamp(config.lastUpdatedSecs))

    // No endpoint here on purpose. The registration API answers in WireGuard
    // terms and returns a peer this app never dials; the address the session
    // actually uses belongs to the session and is shown under Live tunnel.

    SectionLabel("Local address")
    DiagnosticRow("IPv4", config.localAddressIpv4.ifBlank { "—" })

    SectionLabel("Keys")
    DiagnosticRow(
        "Pinned endpoint key",
        config.pinnedEndpointKey.ifBlank { "not enrolled" },
        monospace = true
    )

    SectionLabel("Secrets")
    // The licence key is a credential, so it is revealed on the same deliberate
    // action as the private key rather than sitting in the clear above it —
    // where a screenshot of the diagnostics screen would carry it away.
    DiagnosticRow(
        "License key",
        if (revealSecrets) config.licenseKey.ifBlank { "—" } else HIDDEN_SECRET,
        monospace = true
    )
    DiagnosticRow(
        "Registration key",
        if (revealSecrets) config.registrationKey.ifBlank { "—" } else HIDDEN_SECRET,
        monospace = true
    )
    TextButton(onClick = onToggleReveal) {
        Text(if (revealSecrets) "Hide secrets" else "Reveal secrets")
    }

    SectionLabel("Storage")
    DiagnosticRow("Config file", config.configFilePath, monospace = true)
}

/** Placeholder shown in place of a credential until it is deliberately revealed. */
private const val HIDDEN_SECRET = "•••••••• (tap reveal)"

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
    // "Connected" alone cannot distinguish MASQUE from a silent fallback to
    // a different transport, which is the first thing to establish in any report about
    // images not loading behind a restrictive firewall.
    DiagnosticRow(
        "Transport",
        when (d.protocol) {
            "masque" -> "MASQUE (HTTP/3, UDP 443)"
            else -> d.protocol.ifBlank { "unknown" }
        }
    )
    // The address the session actually dials. Not from the account: the
    // registration API only returns WireGuard endpoints, so the MASQUE data
    // plane is a constant of the transport.
    DiagnosticRow("Endpoint", "${d.endpointAddress}:${d.endpointPort}")
    DiagnosticRow("SNI", d.endpointSni)
    DiagnosticRow(
        "Last handshake",
        d.lastHandshakeSecs?.let { "${it}s ago" } ?: "never"
    )
    DiagnosticRow("Sent", formatBytes(d.txBytes))
    DiagnosticRow("Received", formatBytes(d.rxBytes))
    // Loss and RTT are gone rather than shown as "not measured": quiche tracks
    // both per-path and neither is surfaced, so they were rows that could only
    // ever say nothing.
}

/**
 * The end-to-end check: does traffic actually leave through the tunnel?
 *
 * Connection state and byte counters describe a session that exists. They
 * cannot say a request completed, that Cloudflare counted it as WARP, or that
 * the address an image server would be handed is not the user's own. Only the
 * exit can answer that, so this asks it.
 */
@Composable
private fun EndToEndSection(state: VerifyState, enabled: Boolean, onRun: () -> Unit) {
    SectionLabel("End-to-end check")

    when (state) {
        is VerifyState.Idle -> Text(
            text = "Fetches Cloudflare's trace endpoint through the tunnel and " +
                "reports the address the far end sees.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is VerifyState.Running -> CenteredProgress("Checking through the tunnel…")

        is VerifyState.Failed -> DiagnosticRow(
            "Result",
            state.message,
            valueColor = MaterialTheme.colorScheme.error
        )

        is VerifyState.Done -> {
            val r = state.result
            DiagnosticRow(
                "Result",
                if (r.warpActive) "Traffic is leaving via WARP" else "NOT going through WARP",
                valueColor = if (r.warpActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            DiagnosticRow("Reported by edge", "warp=${r.warp.ifBlank { "absent" }}")
            // The address an image server would be given. If this is the user's
            // own, the proxy is not doing its job however healthy it looks.
            DiagnosticRow("Egress IP", r.egressIp.ifBlank { "—" }, monospace = true)
            DiagnosticRow("Colo", r.colo.ifBlank { "—" })
            DiagnosticRow(
                "Carried by tunnel",
                "${formatBytes(r.txBytes)} sent, ${formatBytes(r.rxBytes)} received"
            )
            if (r.txBytes == 0uL && r.rxBytes == 0uL) {
                // A reply that cost the tunnel nothing did not come through it.
                DiagnosticRow(
                    "Warning",
                    "The tunnel carried no bytes for this check",
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    TextButton(enabled = enabled && state !is VerifyState.Running, onClick = onRun) {
        Text(if (state is VerifyState.Idle) "Run check" else "Run again")
    }
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

/**
 * Render the diagnostics as pasteable text.
 *
 * Credentials are redacted unless the user has already revealed them on screen.
 * This text is copied to share in bug reports, and a Copy button that silently
 * carries an account's licence key into a public issue is not a debugging
 * convenience. Revealing first is the deliberate act that opts into including
 * them; the placeholder says so rather than omitting the line, so a reader can
 * tell a redacted value from a missing one.
 */
private fun formatForClipboard(
    config: WarpStoredConfig,
    live: WarpDiagnostics?,
    includeSecrets: Boolean
): String = buildString {
    fun secret(value: String) = when {
        !includeSecrets -> "<redacted — reveal secrets to include>"
        value.isBlank() -> "<empty>"
        else -> value
    }

    appendLine("# Stored configuration")
    appendLine("has_config=${config.hasConfig}")
    appendLine("tunnel_active=${config.tunnelActive}")
    appendLine("warp_enabled=${config.warpEnabled}")
    appendLine("account_type=${config.accountType}")
    appendLine("account_id=${config.accountId}")
    appendLine("license_key=${secret(config.licenseKey)}")
    appendLine("last_provisioned=${formatTimestamp(config.lastUpdatedSecs)}")
    appendLine("local_address_ipv4=${config.localAddressIpv4}")
    appendLine("pinned_endpoint_key=${config.pinnedEndpointKey}")
    appendLine("registration_key=${secret(config.registrationKey)}")
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
    appendLine("endpoint=${live.endpointAddress}:${live.endpointPort}")
    appendLine("endpoint_sni=${live.endpointSni}")
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
