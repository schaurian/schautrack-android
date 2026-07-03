package to.schauer.schautrack

import android.util.AndroidRuntimeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// isWebViewException matches on javaClass.name.contains(...), so the marker
// only needs to appear somewhere in these classes' fully-qualified names.
private class FakeMissingWebViewPackageException : Exception("provider gone")
private class FakeWebViewFactoryLoadError : RuntimeException("factory failed")

// Robolectric provides the real android.util.AndroidRuntimeException on the
// JVM. compileSdk 37 is ahead of what Robolectric ships android-all jars for,
// so pin SDK 36.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SchautrackAppTest {

    @Test
    fun `class name containing MissingWebViewPackage matches`() {
        assertTrue(SchautrackApp.isWebViewException(FakeMissingWebViewPackageException()))
    }

    @Test
    fun `class name containing WebViewFactory matches`() {
        assertTrue(SchautrackApp.isWebViewException(FakeWebViewFactoryLoadError()))
    }

    @Test
    fun `AndroidRuntimeException mentioning WebView matches`() {
        assertTrue(
            SchautrackApp.isWebViewException(
                AndroidRuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException: Failed to load WebView provider")
            )
        )
    }

    @Test
    fun `AndroidRuntimeException message check is case-insensitive`() {
        assertTrue(SchautrackApp.isWebViewException(AndroidRuntimeException("cannot create webview instance")))
    }

    @Test
    fun `AndroidRuntimeException without WebView in the message does not match`() {
        assertFalse(SchautrackApp.isWebViewException(AndroidRuntimeException("calling startActivity() from outside an Activity")))
    }

    @Test
    fun `matching exception nested two causes deep matches`() {
        val throwable = RuntimeException(
            "outer",
            IllegalStateException("middle", FakeMissingWebViewPackageException())
        )
        assertTrue(SchautrackApp.isWebViewException(throwable))
    }

    @Test
    fun `plain RuntimeException chain does not match`() {
        val throwable = RuntimeException(
            "outer",
            IllegalStateException("middle", IllegalArgumentException("inner"))
        )
        assertFalse(SchautrackApp.isWebViewException(throwable))
    }
}
