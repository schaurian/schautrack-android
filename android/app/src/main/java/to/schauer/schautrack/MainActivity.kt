package to.schauer.schautrack

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import to.schauer.schautrack.databinding.ActivityMainBinding

private const val START_URL = "https://schautrack.schauer.to/"
private const val PREFS_NAME = "schautrack_prefs"
private const val KEY_LAST_SEEN = "last_seen_at"
private const val REFRESH_THRESHOLD_MS = 15 * 60 * 1000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var hasError = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val webView = binding.webView

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString SchautrackApp"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!hasError) {
                    showLoading()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (!hasError) {
                    showContent()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasError = true
                    showError()
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        binding.swipeRefresh.setOnRefreshListener {
            if (!webView.canGoBackOrForward(0)) {
                binding.swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            hasError = false
            webView.reload()
        }

        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefresh.isEnabled = scrollY == 0
        }

        binding.retryButton.setOnClickListener {
            hasError = false
            showLoading()
            webView.loadUrl(START_URL)
        }

        if (savedInstanceState == null) {
            if (isNetworkAvailable()) {
                webView.loadUrl(START_URL)
            } else {
                showError()
            }
        } else {
            showContent()
        }
    }

    private fun showLoading() {
        binding.loadingView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    private fun showContent() {
        binding.loadingView.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    private fun showError() {
        binding.loadingView.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val now = System.currentTimeMillis()
        if (lastSeen > 0 && now - lastSeen >= REFRESH_THRESHOLD_MS) {
            hasError = false
            binding.webView.reload()
        }
        prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }
}
