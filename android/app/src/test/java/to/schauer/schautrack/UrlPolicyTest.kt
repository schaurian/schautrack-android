package to.schauer.schautrack

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SERVER = "https://schautrack.com"

// Robolectric provides the real android.net.Uri on the JVM. compileSdk 37 is
// ahead of what Robolectric ships android-all jars for, so pin SDK 36.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UrlPolicyTest {

    // -- isAuthPage -----------------------------------------------------------

    @Test
    fun `isAuthPage matches login path`() {
        assertTrue(UrlPolicy.isAuthPage("$SERVER/login"))
    }

    @Test
    fun `isAuthPage matches register path`() {
        assertTrue(UrlPolicy.isAuthPage("$SERVER/register"))
    }

    @Test
    fun `isAuthPage ignores a trailing slash`() {
        assertTrue(UrlPolicy.isAuthPage("$SERVER/login/"))
    }

    @Test
    fun `isAuthPage ignores multiple trailing slashes`() {
        assertTrue(UrlPolicy.isAuthPage("$SERVER/register///"))
    }

    @Test
    fun `isAuthPage matches regardless of query string`() {
        assertTrue(UrlPolicy.isAuthPage("$SERVER/login?next=%2Fdashboard"))
    }

    @Test
    fun `isAuthPage is false for null`() {
        assertFalse(UrlPolicy.isAuthPage(null))
    }

    @Test
    fun `isAuthPage is false for dashboard`() {
        assertFalse(UrlPolicy.isAuthPage("$SERVER/dashboard"))
    }

    @Test
    fun `isAuthPage requires an exact path match, deep paths do not count`() {
        assertFalse(UrlPolicy.isAuthPage("$SERVER/login/reset"))
    }

    @Test
    fun `isAuthPage is false for the site root`() {
        assertFalse(UrlPolicy.isAuthPage("$SERVER/"))
    }

    @Test
    fun `isAuthPage is false for garbage input`() {
        assertFalse(UrlPolicy.isAuthPage("::not a url::"))
        assertFalse(UrlPolicy.isAuthPage(""))
        assertFalse(UrlPolicy.isAuthPage("login"))
    }

    // -- normalizeServerUrlInput ----------------------------------------------

    @Test
    fun `normalize prefixes bare host with https`() {
        assertEquals("https://schautrack.com", UrlPolicy.normalizeServerUrlInput("schautrack.com"))
    }

    @Test
    fun `normalize preserves explicit http scheme`() {
        assertEquals("http://192.168.1.10:3000", UrlPolicy.normalizeServerUrlInput("http://192.168.1.10:3000"))
    }

    @Test
    fun `normalize preserves explicit https scheme`() {
        assertEquals("https://staging.schautrack.com", UrlPolicy.normalizeServerUrlInput("https://staging.schautrack.com"))
    }

    @Test
    fun `normalize trims a trailing slash`() {
        assertEquals("https://schautrack.com", UrlPolicy.normalizeServerUrlInput("https://schautrack.com/"))
    }

    @Test
    fun `normalize trims multiple trailing slashes`() {
        assertEquals("https://schautrack.com", UrlPolicy.normalizeServerUrlInput("schautrack.com///"))
    }

    @Test
    fun `normalize trims surrounding whitespace before prefixing`() {
        assertEquals("https://schautrack.com", UrlPolicy.normalizeServerUrlInput("  schautrack.com  "))
    }

    @Test
    fun `normalize returns null for empty input`() {
        assertNull(UrlPolicy.normalizeServerUrlInput(""))
    }

    @Test
    fun `normalize returns null for blank input`() {
        assertNull(UrlPolicy.normalizeServerUrlInput("   "))
    }

    // -- restoreUrlAfterWebViewKill ---------------------------------------------

    private fun restore(
        reason: Int = 0,
        description: String? = null,
        exitAgeMs: Long = 1_000L,
        lastUrl: String? = "$SERVER/meals",
        serverUrl: String = SERVER
    ): String? = UrlPolicy.restoreUrlAfterWebViewKill(reason, description, exitAgeMs, lastUrl, serverUrl)

    @Test
    fun `restore triggers on REASON_DEPENDENCY_DIED`() {
        assertEquals("$SERVER/meals", restore(reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED))
    }

    @Test
    fun `restore triggers on installPackageLI in the description`() {
        assertEquals("$SERVER/meals", restore(description = "PackageManager.installPackageLI updated base.apk"))
    }

    @Test
    fun `restore triggers on the webview package in the description`() {
        assertEquals("$SERVER/meals", restore(description = "dependency com.google.android.webview changed"))
    }

    @Test
    fun `restore triggers on the chrome package in the description`() {
        assertEquals("$SERVER/meals", restore(description = "dependency com.android.chrome changed"))
    }

    @Test
    fun `restore is null for an unrelated reason and description`() {
        assertNull(restore(reason = ApplicationExitInfo.REASON_LOW_MEMORY, description = "low memory kill"))
    }

    @Test
    fun `restore is null when the exit is older than the window`() {
        assertNull(
            restore(
                reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                exitAgeMs = UrlPolicy.WEBVIEW_KILL_RESTORE_WINDOW_MS + 1
            )
        )
    }

    @Test
    fun `restore still triggers exactly at the window boundary`() {
        assertEquals(
            "$SERVER/meals",
            restore(
                reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                exitAgeMs = UrlPolicy.WEBVIEW_KILL_RESTORE_WINDOW_MS
            )
        )
    }

    @Test
    fun `restore is null without a persisted last url`() {
        assertNull(restore(reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED, lastUrl = null))
    }

    @Test
    fun `restore is null when the last url is on a different host`() {
        assertNull(
            restore(
                reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                lastUrl = "https://evil.example/meals"
            )
        )
    }

    @Test
    fun `restore returns the full last url on the happy path`() {
        assertEquals(
            "$SERVER/meals/42?edit=true",
            restore(
                reason = ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                lastUrl = "$SERVER/meals/42?edit=true"
            )
        )
    }
}
