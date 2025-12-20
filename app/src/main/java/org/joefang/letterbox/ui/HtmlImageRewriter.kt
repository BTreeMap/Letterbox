package org.joefang.letterbox.ui

import java.net.URLEncoder

/**
 * Utility for rewriting HTML to proxy remote images through DuckDuckGo.
 * 
 * The DuckDuckGo image proxy endpoint:
 * - Base URL: https://external-content.duckduckgo.com/iu/
 * - Parameter: ?u=[URL-Encoded-Target-Image]
 * 
 * Privacy benefits:
 * - Masks user's IP address from the source server
 * - Strips Referer and User-Agent headers
 * - Upgrades HTTP to HTTPS
 * 
 * Example:
 * Original: https://example.com/tracking-pixel.png
 * Proxied:  https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Fexample.com%2Ftracking-pixel.png
 */
object HtmlImageRewriter {
    
    private const val DUCKDUCKGO_PROXY_BASE = "https://external-content.duckduckgo.com/iu/"
    
    /**
     * Rewrites HTML to proxy all remote images (http:// and https://) through DuckDuckGo.
     * 
     * @param html The original HTML content
     * @param useProxy Whether to apply the proxy rewriting
     * @return HTML with rewritten image URLs if useProxy is true, otherwise original HTML
     */
    fun rewriteImageUrls(html: String, useProxy: Boolean): String {
        if (!useProxy) return html
        
        // Pattern to match img src attributes with http:// or https:// URLs
        // This regex captures:
        // - <img ... src="http(s)://..." ...>
        // - <img ... src='http(s)://...' ...>
        // Group 1: everything before src
        // Group 2: the quote character (" or ')
        // Group 3: the URL
        // Group 4: everything after the URL
        val imgSrcPattern = Regex(
            """(<img\s[^>]*src=)(["'])(https?://[^"']+)(["'][^>]*>)""",
            RegexOption.IGNORE_CASE
        )
        
        return imgSrcPattern.replace(html) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val quote = matchResult.groupValues[2]
            val originalUrl = matchResult.groupValues[3]
            val suffix = matchResult.groupValues[4]
            
            // Don't proxy cid: URLs (inline resources)
            if (originalUrl.startsWith("cid:", ignoreCase = true)) {
                return@replace matchResult.value
            }
            
            // URL-encode the target URL
            val encodedUrl = try {
                URLEncoder.encode(originalUrl, "UTF-8")
            } catch (e: Exception) {
                // If encoding fails, return original
                return@replace matchResult.value
            }
            
            // Construct the proxied URL
            val proxiedUrl = "$DUCKDUCKGO_PROXY_BASE?u=$encodedUrl"
            
            // Reconstruct the img tag with the proxied URL
            "$prefix$quote$proxiedUrl$suffix"
        }
    }
    
    /**
     * Checks if HTML contains remote images that would be proxied.
     * 
     * @param html The HTML content to check
     * @return true if HTML contains http:// or https:// image URLs
     */
    fun containsRemoteImages(html: String): Boolean {
        val remoteImagePattern = Regex(
            """<img\s[^>]*src=["']https?://[^"']+["']""",
            RegexOption.IGNORE_CASE
        )
        return remoteImagePattern.containsMatchIn(html)
    }
}
