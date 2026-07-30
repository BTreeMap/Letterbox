package org.joefang.letterbox.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val CLOUDFLARE_TERMS_URL = "https://www.cloudflare.com/application/terms/"

/**
 * First-launch introduction. Informational only — it gates nothing.
 *
 * Remote images are off until the user asks for them, per message or via the
 * setting, and that request is the opt-in. This screen exists so the tunnel is
 * disclosed before it is ever used, not to collect an acceptance: a separate
 * consent flag previously had to agree with the "Show images" tap for anything
 * to load, and when the two disagreed the failure was silent.
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Welcome to Letterbox",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Letterbox opens .eml and .msg files locally on your device. " +
                    "It never uploads your mail.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Private networking",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Some messages contain remote images, and Letterbox can check GitHub " +
                    "for app updates. To keep your real IP address private, all of this " +
                    "traffic is routed through a Cloudflare WARP tunnel (WireGuard) using a " +
                    "per-device identity created automatically on first use.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Nothing is fetched until you ask for it. Remote images stay blocked " +
                    "until you tap \"Show images\" on a message, or turn them on in Settings. " +
                    "Traffic that does leave the device goes through the tunnel and is " +
                    "subject to Cloudflare's Terms of Service.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLOUDFLARE_TERMS_URL)))
            }) {
                Text("View Cloudflare Terms of Service")
            }
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboardingAcceptButton")
            ) {
                Text("Continue")
            }
        }
    }
}
