package to.schauer.schautrack

import android.app.ApplicationExitInfo
import android.net.Uri

/**
 * Pure URL/decision rules extracted from [MainActivity] so they can be
 * unit-tested. No Android plumbing here beyond [Uri] parsing — the callers
 * keep the SDK checks, system-service lookups, and prefs access.
 */
internal object UrlPolicy {

    /** How recent the process death must be for the last URL to be restored. */
    const val WEBVIEW_KILL_RESTORE_WINDOW_MS = 5 * 60 * 1000L

    /** True iff [url] is exactly the login or register page (trailing slashes ignored). */
    fun isAuthPage(url: String?): Boolean {
        if (url == null) return false
        val uri = Uri.parse(url)
        val path = uri.path?.trimEnd('/') ?: return false
        return path == "/login" || path == "/register"
    }

    /**
     * Normalizes what the user typed into the change-server dialog: trims
     * whitespace, defaults the scheme to https://, and drops trailing slashes.
     * Returns null for blank input (nothing to connect to).
     */
    fun normalizeServerUrlInput(raw: String): String? {
        var newUrl = raw.trim()
        if (newUrl.isEmpty()) return null
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            newUrl = "https://$newUrl"
        }
        return newUrl.trimEnd('/')
    }

    /**
     * Decision core of [MainActivity]'s restore-after-WebView-kill heuristic:
     * given the last process exit (reason, description, age) and the persisted
     * last URL, returns the URL to silently restore — or null to start normally.
     *
     * A WebView/Chrome package update kills every process that has the old
     * provider loaded. That shows up either as REASON_DEPENDENCY_DIED or as an
     * exit description mentioning the package installer (installPackageLI) or
     * the provider package itself. The last URL is only restored if it is still
     * on the configured server.
     */
    fun restoreUrlAfterWebViewKill(
        reason: Int,
        description: String?,
        exitAgeMs: Long,
        lastUrl: String?,
        serverUrl: String
    ): String? {
        if (exitAgeMs > WEBVIEW_KILL_RESTORE_WINDOW_MS) return null
        val desc = description ?: ""
        val killedByWebView = reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED ||
            desc.contains("installPackageLI") ||
            desc.contains("com.google.android.webview") ||
            desc.contains("com.android.chrome")
        if (!killedByWebView) return null
        val last = lastUrl ?: return null
        val serverHost = Uri.parse(serverUrl).host ?: return null
        val lastHost = Uri.parse(last).host ?: return null
        return if (lastHost == serverHost) last else null
    }
}
