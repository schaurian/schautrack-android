package to.schauer.schautrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
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
private const val WEBVIEW_KILL_RESTORE_WINDOW_MS = 5 * 60 * 1000L

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
        if (System.currentTimeMillis() - lastExit.timestamp > WEBVIEW_KILL_RESTORE_WINDOW_MS) return null
        val desc = lastExit.description ?: ""
        val killedByWebView = lastExit.reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED ||
            desc.contains("installPackageLI") ||
            desc.contains("com.google.android.webview") ||
            desc.contains("com.android.chrome")
        if (!killedByWebView) return null
        val last = prefs.getString(KEY_LAST_URL, null) ?: return null
        val serverHost = Uri.parse(serverUrl).host ?: return null
        val lastHost = Uri.parse(last).host ?: return null
        return if (lastHost == serverHost) last else null
    }

    private fun isAuthPage(url: String?): Boolean {
        if (url == null) return false
        val uri = Uri.parse(url)
        val path = uri.path?.trimEnd('/') ?: return false
        return path == "/login" || path == "/register"
    }

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
            setAcceptThirdPartyCookies(wv, true)
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

                startActivity(Intent(Intent.ACTION_VIEW, url))
                return true
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
                updateChangeServerButtonVisibility(url)
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
                        val requestedResources = req.resources
                        if (requestedResources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            if (hasCameraPermission()) {
                                req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                            } else {
                                pendingPermissionRequest = req
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                            }
                        } else {
                            req.grant(requestedResources)
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
            setupWebView(wv)
            webViewAvailable = true
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
                setMargins(48, 32, 48, 0)
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
                var newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        newUrl = "https://$newUrl"
                    }
                    newUrl = newUrl.trimEnd('/')
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
            val isValid = try {
                val url = URL("$newUrl/api/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    json.optString("app") == "schautrack"
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                binding.loadingText.text = getString(R.string.loading)
                if (isValid) {
                    serverUrl = newUrl
                    prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()
                    hasError = false
                    if (webViewAvailable) {
                        try {
                            webView.loadUrl(getStartUrl())
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
        SchautrackApp.webViewInitInProgress = true
        return try {
            WebView(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        } catch (e: Throwable) {
            null
        } finally {
            SchautrackApp.webViewInitInProgress = false
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
        showLoading()
        retryJob = lifecycleScope.launch {
            var delayMs = RETRY_INITIAL_MS
            while (isActive) {
                val healthy = withContext(Dispatchers.IO) { checkHealth() }
                if (healthy) {
                    hasError = false
                    try {
                        webView.loadUrl(getStartUrl())
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

    private fun checkHealth(): Boolean {
        return try {
            val url = URL("$serverUrl/api/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                json.optString("app") == "schautrack"
            } else {
                false
            }
        } catch (e: Exception) {
            false
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
        if (hasError) {
            connectToServer()
            return
        }
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)
        val now = System.currentTimeMillis()
        if (lastSeen > 0 && now - lastSeen >= REFRESH_THRESHOLD_MS) {
            hasError = false
            try {
                webView.reload()
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
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webViewAvailable) {
            try {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return true
                }
            } catch (e: Throwable) {
                recreateWebView()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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
