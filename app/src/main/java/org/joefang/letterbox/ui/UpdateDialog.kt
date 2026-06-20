package org.joefang.letterbox.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.joefang.letterbox.ffi.proxy.UpdateResult

/**
 * Notify-only update dialog.
 *
 * Distribution is handled exclusively through GitHub releases, so this dialog
 * never downloads or installs anything; it links the user to the release page.
 */
@Composable
fun UpdateAvailableDialog(
    result: UpdateResult,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "${result.currentVersion} → ${result.latestVersion}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (result.changelog.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = result.changelog,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (result.releaseUrl.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(result.releaseUrl))
                    )
                }
                onDismiss()
            }) { Text("Open release page") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}

/**
 * Simple confirmation that the app is already up to date.
 */
@Composable
fun UpToDateDialog(
    currentVersion: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Up to date") },
        text = { Text("You are running the latest version ($currentVersion).") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}
