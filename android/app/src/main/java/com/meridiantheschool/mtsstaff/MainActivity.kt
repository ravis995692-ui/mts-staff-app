package com.meridiantheschool.mtsstaff

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.max

/**
 * The whole app is one screen: a WebView showing the MTS staff web app, plus a native
 * "no internet" screen for the very first start. Phone features the web app needs are
 * provided by [WebBridge] (see assets/bridge.js for the web side).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offline: View
    private lateinit var bridge: WebBridge

    private lateinit var bridgeJs: String
    private var docStartScript = false
    private var pageVisible = false
    private var mainFrameFailed = false
    private var webDestroyed = false

    private val appScope: Uri = Uri.parse(BuildConfig.APP_SCOPE)
    private val appOrigin: String = "${appScope.scheme}://${appScope.host}"

    // runtime permissions (notifications, microphone, storage on old phones)
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(granted)
        }

    // <input type="file">
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val started = SystemClock.elapsedRealtime()
        // keep the crest on screen until the page shows (at most 4 s)
        splash.setKeepOnScreenCondition { !pageVisible && SystemClock.elapsedRealtime() - started < 4000 }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }

        // draw the page between the status bar and the navigation bar / keyboard
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        Notifications.createChannel(this)
        WebContent.init(this)
        setupWebView()
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        web.loadUrl(startUrl(intent))
        WebContent.checkForUpdate()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString MTSStaffAndroid/${BuildConfig.VERSION_NAME}"
        }
        // the web app has its own dark theme; don't let WebView darken it a second time
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }

        bridge = WebBridge(this, web)
        web.addJavascriptInterface(bridge, "MTSNative")
        bridgeJs = assets.open("bridge.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, bridgeJs, setOf(appOrigin))
            docStartScript = true
        }

        // the app's own files come from the phone (see WebContent), also for the old service worker
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    WebContent.intercept(request)
            })
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                WebContent.intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return when {
                    url.scheme in setOf("blob", "data", "about", "javascript") -> false
                    isInScope(url) -> false
                    else -> { openExternal(url); true }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                mainFrameFailed = false
                if (!docStartScript) view.evaluateJavascript(bridgeJs, null)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                pageVisible = true
                if (!mainFrameFailed) offline.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageVisible = true
                progress.visibility = View.GONE
                // older WebViews: add the bridge once the page is there (idempotent)
                if (!docStartScript) view.evaluateJavascript(bridgeJs, null)
                if (!mainFrameFailed) offline.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    pageVisible = true
                    offline.visibility = View.VISIBLE
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // the page's process was killed (low memory): rebuild the screen instead of crashing
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                webDestroyed = true
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            // ordinary (non-blob) downloads go to the phone's download manager
            if (url.startsWith("http")) Downloads.enqueue(this, url, contentDisposition, mimeType)
        }
    }

    private fun startUrl(intent: Intent?): String {
        val data = intent?.data
        return if (data != null && isInScope(data)) data.toString() else BuildConfig.APP_URL
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data
        if (data != null && isInScope(data)) web.loadUrl(data.toString())
    }

    fun isInScope(uri: Uri): Boolean = uri.toString().startsWith(BuildConfig.APP_SCOPE)

    fun openExternal(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    // never let a web page start a specific hidden component
                    component = null
                    selector = null
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun retry() {
        offline.visibility = View.GONE
        if (web.url.isNullOrEmpty()) web.loadUrl(BuildConfig.APP_URL) else web.reload()
    }

    private fun handleBack() {
        if (offline.visibility == View.VISIBLE) { finish(); return }
        // let the web app close a dialog / menu or return to its home screen first
        web.evaluateJavascript("window.__mtsBack ? window.__mtsBack() : ''") { result ->
            val handled = result != null && result != "null" && result != "\"\""
            if (!handled) {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        }
    }

    /** Status / navigation bar background follows the web app's theme-color (navy, or dark navy in dark mode). */
    fun applyBarColor(color: Int) {
        root.setBackgroundColor(color)
        val light = ColorUtils.calculateLuminance(color) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    fun askPermission(permission: String, callback: (Boolean) -> Unit) {
        permissionCallback?.invoke(false)
        permissionCallback = callback
        permissionLauncher.launch(permission)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        WebContent.checkForUpdate()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bridge.shutdown()
        if (!webDestroyed) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
