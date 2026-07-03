package to.schauer.schautrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import to.schauer.schautrack.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER
private const val PREFS_NAME = "schautrack_prefs"
private const val KEY_LAST_SEEN = "last_seen_at"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_LAST_URL = "last_url"
private const val REFRESH_THRESHOLD_MS = 15 * 60 * 1000L
private const val RETRY_INITIAL_MS = 2000L
private const val RETRY_MAX_MS = 30_000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var hasError = false

    override fun getSystemService(name: String): Any? {
        if (SchautrackApp.webViewInitInProgress && name == Context.WINDOW_SERVICE) {
            return SchautrackApp.createSilentWindowManager(super.getSystemService(name) as WindowManager)
        }
        return super.getSystemService(name)
    }

    private var webViewAvailable = false
    private var serverUrl: String = DEFAULT_SERVER
    private lateinit var webView: WebView
    private var retryJob: Job? = null
    private var fastRestoreInProgress = false
    private var webViewPackageVersion: String? = null

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingPermissionRequest: android.webkit.PermissionRequest? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (retryJob?.isActive != true) return@runOnUiThread
                if (!webViewAvailable) initWebViewWithRetry() else connectToServer()
            }
        }
    }

    private var pendingRestoreUrl: String? = null

    // Back handling via the dispatcher (not onKeyDown), so it keeps working on
    // Android 16+ where predictive back stops delivering KEYCODE_BACK.
    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            try {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
            } catch (e: Throwable) {
                recreateWebView()
                return
            }
            // Nothing to go back to — disable and re-dispatch so the system
            // performs its default (exit) behavior without re-entering here.
            isEnabled = false
            this@MainActivity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun updateBackCallbackState() {
        onBackPressedCallback.isEnabled = webViewAvailable &&
            try { webView.canGoBack() } catch (_: Throwable) { false }
    }

    private fun getStartUrl(): String {
        pendingRestoreUrl?.let {
            pendingRestoreUrl = null
            return it
        }
        return "$serverUrl/dashboard"
    }

    private fun consumeRestoreUrlIfKilledByWebView(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val lastExit = try {
            am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null
        return UrlPolicy.restoreUrlAfterWebViewKill(
            reason = lastExit.reason,
            description = lastExit.description,
            exitAgeMs = System.currentTimeMillis() - lastExit.timestamp,
            lastUrl = prefs.getString(KEY_LAST_URL, null),
            serverUrl = serverUrl
        )
    }

    private fun isAuthPage(url: String?): Boolean = UrlPolicy.isAuthPage(url)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            pendingPermissionRequest?.let { request ->
                runOnUiThread {
                    if (cameraGranted) {
                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
                pendingPermissionRequest = null
                return@registerForActivityResult
            }
            if (cameraGranted) {
                openFileChooserWithCamera()
            } else {
                openFileChooserWithoutCamera()
            }
        }

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER

        if (savedInstanceState == null) {
            pendingRestoreUrl = consumeRestoreUrlIfKilledByWebView()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val initialWebView = tryCreateWebView()
        if (initialWebView != null) {
            trySetupWebView(initialWebView)
        }

        binding.retryButton.setOnClickListener {
            if (!webViewAvailable) initWebViewWithRetry() else connectToServer()
        }

        binding.changeServerButton.setOnClickListener {
            showChangeServerDialog()
        }

        binding.errorChangeServerButton.setOnClickListener {
            showChangeServerDialog()
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(networkCallback)

        if (!webViewAvailable) {
            initWebViewWithRetry()
        } else if (savedInstanceState == null) {
            connectToServer()
        } else {
            try {
                webView.restoreState(savedInstanceState)
                showContent()
            } catch (e: Throwable) {
                recreateWebView()
            }
        }
    }

    private fun updateChangeServerButtonVisibility(url: String?) {
        binding.changeServerButton.visibility = if (isAuthPage(url)) View.VISIBLE else View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Single-first-party app: the session cookie is first-party, so
            // third-party cookies are unnecessary attack/tracking surface.
            setAcceptThirdPartyCookies(wv, false)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString SchautrackApp"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                wv.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
            )
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val serverHost = Uri.parse(serverUrl).host ?: return false

                if (url.host == serverHost) {
                    return false
                }

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) {
                    // No installed app can handle this URI (e.g. a mailto: on a
                    // device with no mail client, or market:// on a de-Googled
                    // phone). Swallow it instead of crashing.
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (fastRestoreInProgress) return
                if (!hasError) {
                    showLoading()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.swipeRefresh.isRefreshing = false
                if (!hasError) {
                    showContent()
                }
                fastRestoreInProgress = false
                updateChangeServerButtonVisibility(url)
                updateBackCallbackState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    hasError = true
                    connectToServer()
                    return
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    if (statusCode >= 500) {
                        hasError = true
                        connectToServer()
                        return
                    }
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateChangeServerButtonVisibility(url)
                updateBackCallbackState()
                if (url != null && !isAuthPage(url)) {
                    prefs.edit().putString(KEY_LAST_URL, url).apply()
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                recreateWebView()
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                if (hasCameraPermission()) {
                    openFileChooserWithCamera()
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.let { req ->
                    runOnUiThread {
                        // Only honor permission requests coming from the configured
                        // server's own origin — never from third-party iframes or
                        // an unexpected host.
                        val serverHost = Uri.parse(serverUrl).host
                        if (serverHost == null || req.origin?.host != serverHost) {
                            req.deny()
                            return@runOnUiThread
                        }
                        // The app only ever needs the camera (food scanning). Grant
                        // video capture; deny anything else (audio, protected media, …).
                        if (req.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            if (hasCameraPermission()) {
                                req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                            } else {
                                pendingPermissionRequest = req
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                            }
                        } else {
                            req.deny()
                        }
                    }
                }
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            try {
                if (!wv.canGoBackOrForward(0)) {
                    binding.swipeRefresh.isRefreshing = false
                    return@setOnRefreshListener
                }
                hasError = false
                wv.reload()
            } catch (e: Throwable) {
                binding.swipeRefresh.isRefreshing = false
                recreateWebView()
            }
        }

        wv.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefresh.isEnabled = scrollY == 0
        }
    }

    private fun trySetupWebView(wv: WebView): Boolean {
        return try {
            webView = wv
            binding.webViewContainer.addView(wv)
            withWebViewDialogSuppressed { setupWebView(wv) }
            webViewAvailable = true
            webViewPackageVersion = currentWebViewVersion()
            true
        } catch (e: Throwable) {
            try { binding.webViewContainer.removeView(wv) } catch (_: Throwable) {}
            try { wv.destroy() } catch (_: Throwable) {}
            webViewAvailable = false
            false
        }
    }

    private fun recreateWebView() {
        try { binding.webViewContainer.removeView(webView) } catch (_: Throwable) {}
        try { webView.destroy() } catch (_: Throwable) {}
        webViewAvailable = false
        initWebViewWithRetry()
    }

    private fun showChangeServerDialog() {
        val currentUrl = if (webViewAvailable) try { webView.url } catch (_: Throwable) { null } else null
        val currentHost = if (currentUrl != null) {
            val uri = Uri.parse(currentUrl)
            "${uri.scheme}://${uri.host}"
        } else {
            serverUrl
        }

        val textInputLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.server_url)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val density = resources.displayMetrics.density
                setMargins((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            }
        }

        val input = TextInputEditText(textInputLayout.context).apply {
            setText(currentHost)
        }
        textInputLayout.addView(input)

        MaterialAlertDialogBuilder(this, R.style.Theme_Schautrack_Dialog)
            .setTitle(R.string.change_server)
            .setView(textInputLayout)
            .setPositiveButton(R.string.connect) { _, _ ->
                val newUrl = UrlPolicy.normalizeServerUrlInput(input.text.toString())
                if (newUrl != null) {
                    validateAndConnectToServer(newUrl)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun validateAndConnectToServer(newUrl: String) {
        binding.loadingText.text = getString(R.string.validating_server)
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val isValid = probeHealth(newUrl, 10000)

            withContext(Dispatchers.Main) {
                binding.loadingText.text = getString(R.string.loading)
                if (isValid) {
                    serverUrl = newUrl
                    prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()
                    hasError = false
                    if (webViewAvailable) {
                        try {
                            withWebViewDialogSuppressed { webView.loadUrl(getStartUrl()) }
                        } catch (e: Throwable) {
                            recreateWebView()
                        }
                    } else {
                        initWebViewWithRetry()
                    }
                } else {
                    showContent()
                    MaterialAlertDialogBuilder(this@MainActivity, R.style.Theme_Schautrack_Dialog)
                        .setTitle(R.string.invalid_server)
                        .setMessage(newUrl)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    // -- Retry logic ----------------------------------------------------------

    private fun tryCreateWebView(): WebView? {
        // Quick pre-check: skip creation entirely if the WebView package is missing.
        try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return null
            packageManager.getPackageInfo(pkg.packageName, 0)
        } catch (e: Throwable) {
            return null
        }

        // Suppress any in-process dialog that Google's WebView code might show
        // when the provider is unavailable (e.g. mid-update). We return a no-op
        // WindowManager from getSystemService() so any dialog show() is a no-op,
        // while the exception still propagates to our catch block.
        return try {
            withWebViewDialogSuppressed {
                WebView(this).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Runs [block] with the silent-WindowManager guard active, so any in-process
     * dialog the system WebView/Chrome code tries to show (e.g. the
     * "Check that Google Play is enabled" split-check dialog when the WebView
     * package is mid-update) is swallowed instead of flashing on screen.
     * Re-entrant: restores the previous flag value on exit.
     */
    private fun <T> withWebViewDialogSuppressed(block: () -> T): T {
        val previous = SchautrackApp.webViewInitInProgress
        SchautrackApp.webViewInitInProgress = true
        return try {
            block()
        } finally {
            SchautrackApp.webViewInitInProgress = previous
        }
    }

    /** Identity (package + version) of the currently-selected WebView provider, or null if unknown. */
    private fun currentWebViewVersion(): String? {
        return try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return null
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") pkg.versionCode.toString()
            }
            "${pkg.packageName}:${pkg.versionName}:$code"
        } catch (_: Throwable) {
            null
        }
    }

    private fun initWebViewWithRetry() {
        retryJob?.cancel()
        showLoading()
        retryJob = lifecycleScope.launch {
            var delayMs = RETRY_INITIAL_MS
            while (isActive) {
                val wv = tryCreateWebView()
                if (wv != null && trySetupWebView(wv)) {
                    connectToServer()
                    return@launch
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    private fun connectToServer() {
        retryJob?.cancel()

        val restoreUrl = pendingRestoreUrl
        if (restoreUrl != null) {
            // The OS killed us mid-session because Play Store updated
            // com.google.android.webview. The server was reachable seconds ago,
            // so probe + spinner are pure latency on the recovery path.
            pendingRestoreUrl = null
            hasError = false
            fastRestoreInProgress = true
            showContent()
            try {
                withWebViewDialogSuppressed { webView.loadUrl(restoreUrl) }
            } catch (e: Throwable) {
                fastRestoreInProgress = false
                recreateWebView()
            }
            return
        }

        fastRestoreInProgress = false
        showLoading()
        retryJob = lifecycleScope.launch {
            var delayMs = RETRY_INITIAL_MS
            while (isActive) {
                val healthy = withContext(Dispatchers.IO) { probeHealth(serverUrl, 5000) }
                if (healthy) {
                    hasError = false
                    try {
                        withWebViewDialogSuppressed { webView.loadUrl(getStartUrl()) }
                    } catch (e: Throwable) {
                        recreateWebView()
                    }
                    return@launch
                }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    /**
     * Blocking GET of [base]/api/health; true iff it responds 200 with the
     * schautrack marker. Always releases the connection, even on error — this
     * runs inside an unbounded retry loop, so a leak here accumulates.
     */
    private fun probeHealth(base: String, timeoutMs: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/api/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response).optString("app") == "schautrack"
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    // -- View state ------------------------------------------------------------

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

    // -- Lifecycle -------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (retryJob?.isActive == true) return
        if (!webViewAvailable) {
            initWebViewWithRetry()
            return
        }
        // If the system WebView/Chrome package was updated while we were
        // backgrounded, the implementation loaded into this process is now
        // stale. Touching the existing WebView would trip the provider's
        // "Check that Google Play is enabled" split check — a visible dialog,
        // usually followed by a process kill. Recreate through the suppression
        // window instead: any such dialog is routed into the silent path, and
        // if the OS then kills us the restore-URL logic recovers the page.
        val webViewVersion = currentWebViewVersion()
        if (webViewPackageVersion != null && webViewVersion != null &&
            webViewVersion != webViewPackageVersion
        ) {
            recreateWebView()
            return
        }
        if (hasError) {
            connectToServer()
            return
        }
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val now = System.currentTimeMillis()
        if (lastSeen > 0 && now - lastSeen >= REFRESH_THRESHOLD_MS) {
            hasError = false
            try {
                withWebViewDialogSuppressed { webView.reload() }
            } catch (e: Throwable) {
                recreateWebView()
                return
            }
        }
        prefs.edit().putLong(KEY_LAST_SEEN, now).apply()
    }

    override fun onStop() {
        super.onStop()
        retryJob?.cancel()
        // Persist cookies to disk now: a WebView-update process kill right after
        // login would otherwise drop the just-set session cookie.
        CookieManager.getInstance().flush()
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (webViewAvailable) {
            try { webView.saveState(outState) } catch (_: Throwable) {}
        }
    }

    // -- Camera / file chooser -------------------------------------------------

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "JPEG_${timestamp}_"
        val storageDir = cacheDir
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    private fun openFileChooserWithCamera() {
        val imageFile = createImageFile()
        cameraImageUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            imageFile
        )

        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        }

        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        val chooserIntent = Intent.createChooser(galleryIntent, getString(R.string.choose_image))
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))

        fileChooserLauncher.launch(chooserIntent)
    }

    private fun openFileChooserWithoutCamera() {
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        val chooserIntent = Intent.createChooser(galleryIntent, getString(R.string.choose_image))
        fileChooserLauncher.launch(chooserIntent)
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            val result = data?.data?.let { arrayOf(it) }
                ?: cameraImageUri?.let { arrayOf(it) }
            fileChooserCallback?.onReceiveValue(result)
        } else {
            fileChooserCallback?.onReceiveValue(null)
        }
        fileChooserCallback = null
        cameraImageUri = null
    }
}
