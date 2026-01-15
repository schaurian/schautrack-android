package to.schauer.schautrack

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import to.schauer.schautrack.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER
private const val PREFS_NAME = "schautrack_prefs"
private const val KEY_LAST_SEEN = "last_seen_at"
private const val KEY_SERVER_URL = "server_url"
private const val REFRESH_THRESHOLD_MS = 15 * 60 * 1000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var hasError = false
    private var serverUrl: String = DEFAULT_SERVER

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingPermissionRequest: android.webkit.PermissionRequest? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private fun getStartUrl(): String = "$serverUrl/"

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

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
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString SchautrackApp"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val serverHost = Uri.parse(serverUrl).host ?: return false

                // Allow navigation within the configured server
                if (url.host == serverHost) {
                    return false
                }

                // Open external links in system browser
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
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    hasError = true
                    showError()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
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
            webView.loadUrl(getStartUrl())
        }

        binding.changeServerButton.setOnClickListener {
            showChangeServerDialog()
        }

        binding.errorChangeServerButton.setOnClickListener {
            showChangeServerDialog()
        }

        if (savedInstanceState == null) {
            showLoading()
            if (isNetworkAvailable()) {
                webView.loadUrl(getStartUrl())
            } else {
                showError()
            }
        } else {
            webView.restoreState(savedInstanceState)
            showContent()
        }
    }

    private fun updateChangeServerButtonVisibility(url: String?) {
        binding.changeServerButton.visibility = if (isAuthPage(url)) View.VISIBLE else View.GONE
    }

    private fun showChangeServerDialog() {
        val currentUrl = binding.webView.url
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
                    serverUrl = newUrl
                    prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()
                    hasError = false
                    showLoading()
                    binding.webView.loadUrl(getStartUrl())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
