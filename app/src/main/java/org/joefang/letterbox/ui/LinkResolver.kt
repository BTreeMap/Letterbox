package org.joefang.letterbox.ui

import android.net.Uri

data class LinkResolution(
    val fixedUrl: String,
    val openAllowed: Boolean,
    val openUri: Uri?
)

object LinkResolver {
    private val hardBlockedSchemes = setOf(
        "javascript",
        "data",
        "file",
        "content",
        "intent",
        "about"
    )

    fun resolve(rawUrl: String): LinkResolution {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            return LinkResolution(fixedUrl = "", openAllowed = false, openUri = null)
        }

        val initialUri = Uri.parse(trimmed)
        val scheme = initialUri.scheme?.lowercase()
        if (scheme != null) {
            if (scheme in hardBlockedSchemes) {
                return LinkResolution(fixedUrl = trimmed, openAllowed = false, openUri = null)
            }
            if (scheme == "http" || scheme == "https") {
                return LinkResolution(fixedUrl = trimmed, openAllowed = true, openUri = initialUri)
            }
            return LinkResolution(fixedUrl = trimmed, openAllowed = false, openUri = null)
        }

        val baseCandidate = if (trimmed.startsWith("//")) {
            "https:$trimmed"
        } else {
            trimmed
        }

        val withSchemeCandidate = if (baseCandidate.startsWith("/") || baseCandidate.startsWith(".")) {
            baseCandidate
        } else {
            val boundaryIndex = baseCandidate.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it == -1) baseCandidate.length else it }
            val hostPart = baseCandidate.substring(0, boundaryIndex)
            if (hostPart.contains('.')) {
                "https://$baseCandidate"
            } else {
                baseCandidate
            }
        }

        val candidates = buildList {
            add(withSchemeCandidate)
            if (withSchemeCandidate.contains(' ')) {
                add(withSchemeCandidate.replace(" ", "%20"))
            }
        }

        for (candidate in candidates) {
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                continue
            }
            val parsed = Uri.parse(candidate)
            val parsedScheme = parsed.scheme?.lowercase()
            if (parsedScheme == "http" || parsedScheme == "https") {
                return LinkResolution(fixedUrl = candidate, openAllowed = true, openUri = parsed)
            }
        }

        return LinkResolution(fixedUrl = trimmed, openAllowed = false, openUri = null)
    }
}
