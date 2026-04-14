package to.schauer.schautrack

import android.app.Application
import android.content.Context
import android.view.WindowManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

class SchautrackApp : Application() {

    override fun getSystemService(name: String): Any? {
        if (webViewInitInProgress && name == Context.WINDOW_SERVICE) {
            return createSilentWindowManager(super.getSystemService(name) as WindowManager)
        }
        return super.getSystemService(name)
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
    }
}
