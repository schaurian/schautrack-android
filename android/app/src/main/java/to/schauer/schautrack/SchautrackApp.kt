package to.schauer.schautrack

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

class SchautrackApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installWebViewCrashGuard()
    }

    override fun getSystemService(name: String): Any? {
        if (webViewInitInProgress && name == Context.WINDOW_SERVICE) {
            return createSilentWindowManager(super.getSystemService(name) as WindowManager)
        }
        return super.getSystemService(name)
    }

    /**
     * Installs a global uncaught-exception handler that swallows WebView
     * initialization crashes on background threads. Without this, a
     * background-thread crash triggers the Android "app has stopped" dialog.
     * The main-thread retry loop handles recovery.
     */
    private fun installWebViewCrashGuard() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread != Looper.getMainLooper().thread && isWebViewException(throwable)) {
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        @Volatile
        var webViewInitInProgress = false

        fun createSilentWindowManager(real: WindowManager): WindowManager {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                WindowManager::class.java.classLoader,
                arrayOf(WindowManager::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "addView", "removeView", "updateViewLayout", "removeViewImmediate" -> null
                    else -> try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as WindowManager
        }

        fun isWebViewException(throwable: Throwable): Boolean {
            var t: Throwable? = throwable
            while (t != null) {
                val name = t.javaClass.name
                if (name.contains("MissingWebViewPackage") ||
                    name.contains("WebViewFactory") ||
                    (name == "android.util.AndroidRuntimeException" &&
                        t.message?.contains("WebView", ignoreCase = true) == true)
                ) {
                    return true
                }
                t = t.cause
            }
            return false
        }
    }
}
