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
 * First-launch onboarding that establishes network consent.
 *
 * Remote images and update checks are tunnelled through Cloudflare WARP so the
 * user's real IP is never exposed. Continuing into the app constitutes agreement
 * to Cloudflare's Terms of Service; declining keeps all network features off.
 */
@Composable
fun OnboardingScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
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
                text = "By continuing to use the app you agree to Cloudflare's Terms of Service. " +
                    "If you prefer, you can continue without enabling any network features.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLOUDFLARE_TERMS_URL)))
            }) {
                Text("View Cloudflare Terms of Service")
            }
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboardingAcceptButton")
            ) {
                Text("Agree & continue")
            }
            TextButton(
                onClick = onDecline,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboardingDeclineButton")
            ) {
                Text("Continue without network features")
            }
        }
    }
}
