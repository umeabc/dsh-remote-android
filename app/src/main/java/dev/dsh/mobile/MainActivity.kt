package dev.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reference-style light UI. The Activity owns no transport: it observes
 * [ConnectionService] snapshots, starts/stops the service, and renders
 * Home / Pair / Session screens plus a Settings sheet in one Activity.
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, PAIR, SESSION }
    private enum class PairMode { SCAN, MANUAL }

    private var screen = Screen.HOME
    private var pairMode = PairMode.SCAN
    private var lastSnapshot: ConnectionService.Snapshot = ConnectionService.snapshot
    private var loadedPort: Int = 0
    private var pendingConnectLink: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var awaitingPicker = false
    private var loadRetries = 0

    // Views
    private lateinit var rootContainer: View
    private lateinit var homeLayer: View
    private lateinit var homeDeviceCard: View
    private lateinit var homeEmptyCard: View
    private lateinit var homeDeviceHost: TextView
    private lateinit var homeDeviceDot: View
    private lateinit var homeDeviceStatus: TextView
    private lateinit var sessionLayer: View
    private lateinit var sessionAppBar: View
    private lateinit var sessionTitle: TextView
    private lateinit var sessionDot: View
    private lateinit var sessionStatusText: TextView
    private lateinit var sessionProgress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var sessionErrorOverlay: View
    private lateinit var connectingSpinner: View
    private lateinit var errorIcon: ImageView
    private lateinit var errorTitle: TextView
    private lateinit var errorBody: TextView
    private lateinit var btnErrorRetry: View
    private lateinit var pairLayer: View
    private lateinit var tabScan: TextView
    private lateinit var tabManual: TextView
    private lateinit var pairScanSection: View
    private lateinit var pairManualSection: View
    private lateinit var pairInput: EditText
    private lateinit var pairErrorText: TextView
    private lateinit var settingsOverlay: View
    private lateinit var settingsValRelay: TextView
    private lateinit var settingsValCode: TextView
    private lateinit var settingsValVersion: TextView

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            showScreen(Screen.PAIR)
            setPairMode(PairMode.MANUAL)
            pairInput.setText(contents)
            submitPair()
        }
    }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            awaitingPicker = false
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            if (callback == null) return@registerForActivityResult
            if (uris == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            // Copy picks into our own cache and hand the page FileProvider
            // URIs: some providers (cloud pickers, picture providers) do not
            // grant a stable read to the WebView's worker, which surfaces as
            // a failed upload even though the file is visible in the UI.
            Thread({
                val ready = uris.map { uri ->
                    runCatching {
                        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    materialize(uri) ?: uri
                }.toTypedArray()
                runOnUiThread { callback.onReceiveValue(ready) }
            }, "dsh-pick").also { it.isDaemon = true; it.start() }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val link = pendingConnectLink
            pendingConnectLink = null
            if (!granted) {
                // Denial must not block connecting: the FGS notification is
                // simply hidden while the session keeps running.
                Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_SHORT).show()
            }
            link?.let { ConnectionService.start(this, it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        configureWebView()

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            sessionAppBar.updatePadding(top = bars.top)
            // 48dp of content plus the status-bar inset; a fixed height would
            // squeeze the row into the inset and clip it.
            sessionAppBar.minimumHeight = (48 * resources.displayMetrics.density).toInt() + bars.top
            pairLayer.updatePadding(top = bars.top)
            homeLayer.updatePadding(top = bars.top)
            rootContainer.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    settingsOverlay.visibility == View.VISIBLE -> showSettings(false)
                    screen == Screen.PAIR -> showScreen(Screen.HOME)
                    screen == Screen.SESSION ->
                        if (webView.canGoBack()) webView.goBack() else showScreen(Screen.HOME)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        findViewById<View>(R.id.btn_home_settings).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.btn_home_add_scan).setOnClickListener {
            showScreen(Screen.PAIR); setPairMode(PairMode.SCAN)
        }
        findViewById<View>(R.id.btn_home_add_manual).setOnClickListener {
            showScreen(Screen.PAIR); setPairMode(PairMode.MANUAL)
        }
        findViewById<View>(R.id.btn_home_open).setOnClickListener { openSession() }
        findViewById<View>(R.id.btn_pair_back).setOnClickListener { showScreen(Screen.HOME) }
        tabScan.setOnClickListener { setPairMode(PairMode.SCAN) }
        tabManual.setOnClickListener { setPairMode(PairMode.MANUAL) }
        findViewById<View>(R.id.btn_launch_scanner).setOnClickListener { launchScanner() }
        findViewById<View>(R.id.btn_pair_paste).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.btn_submit_pair).setOnClickListener { submitPair() }
        findViewById<View>(R.id.btn_session_back).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else showScreen(Screen.HOME)
        }
        findViewById<View>(R.id.btn_session_refresh).setOnClickListener { refreshSession() }
        findViewById<View>(R.id.btn_session_settings).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.settings_sheet_close).setOnClickListener { showSettings(false) }
        findViewById<View>(R.id.settings_btn_close).setOnClickListener { showSettings(false) }
        findViewById<View>(R.id.settings_btn_disconnect).setOnClickListener { disconnectAndClear() }
        settingsOverlay.setOnClickListener { showSettings(false) }
        findViewById<View>(R.id.settings_card).setOnClickListener { /* absorb clicks */ }
        btnErrorRetry.setOnClickListener { retry() }

        settingsValVersion.text = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "0.1.0"
        }

        refreshHomeCard()
        showScreen(Screen.HOME)
    }

    override fun onStart() {
        super.onStart()
        ConnectionService.observe(::onSnapshot)
        resumeIfNeeded()
    }

    override fun onStop() {
        ConnectionService.unobserve(::onSnapshot)
        super.onStop()
    }

    /** Coming back to the foreground after a killed/网络切换 tunnel dials again. */
    private fun resumeIfNeeded() {
        if (awaitingPicker) return
        if (!ConnectionService.desired(this)) return
        val link = prefs().getString(KEY_LINK, null) ?: return
        if (lastSnapshot.phase == ConnectionService.Phase.IDLE ||
            lastSnapshot.phase == ConnectionService.Phase.DISCONNECTED
        ) {
            ConnectionService.start(this, link)
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.root_container)
        homeLayer = findViewById(R.id.home_layer)
        homeDeviceCard = findViewById(R.id.home_device_card)
        homeEmptyCard = findViewById(R.id.home_empty_card)
        homeDeviceHost = findViewById(R.id.home_device_host)
        homeDeviceDot = findViewById(R.id.home_device_dot)
        homeDeviceStatus = findViewById(R.id.home_device_status)
        sessionLayer = findViewById(R.id.session_layer)
        sessionAppBar = findViewById(R.id.session_app_bar)
        sessionTitle = findViewById(R.id.session_title)
        sessionDot = findViewById(R.id.session_status_dot)
        sessionStatusText = findViewById(R.id.session_status_text)
        sessionProgress = findViewById(R.id.session_progress_bar)
        webView = findViewById(R.id.main_webview)
        sessionErrorOverlay = findViewById(R.id.session_error_overlay)
        connectingSpinner = findViewById(R.id.session_connecting_spinner)
        errorIcon = findViewById(R.id.error_icon)
        errorTitle = findViewById(R.id.error_title)
        errorBody = findViewById(R.id.error_body)
        btnErrorRetry = findViewById(R.id.btn_error_retry)
        pairLayer = findViewById(R.id.pair_layer)
        tabScan = findViewById(R.id.tab_scan)
        tabManual = findViewById(R.id.tab_manual)
        pairScanSection = findViewById(R.id.pair_scan_section)
        pairManualSection = findViewById(R.id.pair_manual_section)
        pairInput = findViewById(R.id.pair_input_link)
        pairErrorText = findViewById(R.id.pair_error_text)
        settingsOverlay = findViewById(R.id.settings_overlay)
        settingsValRelay = findViewById(R.id.settings_val_relay)
        settingsValCode = findViewById(R.id.settings_val_code)
        settingsValVersion = findViewById(R.id.settings_val_version)
        sessionTitle.setText(R.string.desktop_name_default)
    }

    // --- Service snapshot rendering -----------------------------------------

    private fun onSnapshot(snapshot: ConnectionService.Snapshot) {
        lastSnapshot = snapshot
        android.util.Log.d("DshApp", "snapshot ${snapshot.phase} port=${snapshot.port} screen=$screen")
        if (snapshot.phase == ConnectionService.Phase.READY && snapshot.port > 0) {
            prefs().edit().putLong(KEY_LAST_CONNECTED, System.currentTimeMillis()).apply()
            loadSessionUrl(snapshot.port)
        }
        renderStatus()
        if (screen == Screen.SESSION) renderSessionState()
    }

    private fun statusColor(): Int = when (lastSnapshot.phase) {
        ConnectionService.Phase.READY, ConnectionService.Phase.CONNECTED -> ContextCompat.getColor(this, R.color.success)
        ConnectionService.Phase.CONNECTING -> ContextCompat.getColor(this, R.color.warning)
        else -> ContextCompat.getColor(this, R.color.danger)
    }

    private fun statusText(): String = when (lastSnapshot.phase) {
        ConnectionService.Phase.READY -> getString(R.string.home_status_online)
        ConnectionService.Phase.CONNECTED, ConnectionService.Phase.CONNECTING -> getString(R.string.status_connecting)
        else -> getString(R.string.home_status_offline)
    }

    private fun renderStatus() {
        val color = statusColor()
        homeDeviceDot.background?.let { tint(it, color) }
        sessionDot.background?.let { tint(it, color) }
        homeDeviceStatus.text = if (lastSnapshot.phase.isLive()) {
            statusText()
        } else {
            val last = prefs().getLong(KEY_LAST_CONNECTED, 0L)
            if (last > 0L) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                getString(R.string.home_status_offline) + " · 上次连接 " + fmt.format(Date(last))
            } else {
                statusText()
            }
        }
        sessionStatusText.text = statusText()
    }

    private fun ConnectionService.Phase.isLive(): Boolean =
        this == ConnectionService.Phase.READY || this == ConnectionService.Phase.CONNECTED ||
            this == ConnectionService.Phase.CONNECTING

    private fun tint(drawable: android.graphics.drawable.Drawable, color: Int) {
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, color)
    }

    private fun refreshHomeCard() {
        val link = prefs().getString(KEY_LINK, null)
        if (link.isNullOrBlank()) {
            homeDeviceCard.visibility = View.GONE
            homeEmptyCard.visibility = View.VISIBLE
        } else {
            homeDeviceCard.visibility = View.VISIBLE
            homeEmptyCard.visibility = View.GONE
            homeDeviceHost.text = Pairing.parse(link)?.let { "${it.host}:${it.port}" }
                ?: getString(R.string.home_status_offline)
        }
        renderStatus()
    }

    // --- Navigation ----------------------------------------------------------

    private fun showScreen(target: Screen) {
        screen = target
        homeLayer.visibility = if (target == Screen.HOME) View.VISIBLE else View.GONE
        pairLayer.visibility = if (target == Screen.PAIR) View.VISIBLE else View.GONE
        sessionLayer.visibility = if (target == Screen.SESSION) View.VISIBLE else View.GONE
        if (target == Screen.HOME) refreshHomeCard()
        if (target == Screen.SESSION) renderSessionState()
    }

    private fun setPairMode(mode: PairMode) {
        pairMode = mode
        pairScanSection.visibility = if (mode == PairMode.SCAN) View.VISIBLE else View.GONE
        pairManualSection.visibility = if (mode == PairMode.MANUAL) View.VISIBLE else View.GONE
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        tabScan.setTextColor(if (mode == PairMode.SCAN) active else inactive)
        tabScan.setBackgroundResource(if (mode == PairMode.SCAN) R.drawable.bg_segmented_active else 0)
        tabManual.setTextColor(if (mode == PairMode.MANUAL) active else inactive)
        tabManual.setBackgroundResource(if (mode == PairMode.MANUAL) R.drawable.bg_segmented_active else 0)
    }

    private fun showSettings(show: Boolean) {
        if (show) {
            val link = prefs().getString(KEY_LINK, null)
            val config = link?.let { Pairing.parse(it) }
            settingsValRelay.text = config?.let { "${it.host}:${it.port}" } ?: "—"
            val room = config?.room
            settingsValCode.text = when {
                room == null -> "—"
                room.length > 8 -> room.take(4) + "••••••••" + room.takeLast(4)
                else -> room
            }
        }
        settingsOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    // --- Actions --------------------------------------------------------------

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setPrompt(getString(R.string.pair_scan_hint))
            setBeepEnabled(false)
            setOrientationLocked(true)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        }
        scanner.launch(options)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) {
            pairInput.setText(text)
            pairErrorText.visibility = View.GONE
        } else {
            Toast.makeText(this, R.string.pair_clipboard_empty, Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitPair() {
        val link = pairInput.text.toString().trim()
        if (Pairing.parse(link) == null) {
            pairErrorText.visibility = View.VISIBLE
            return
        }
        pairErrorText.visibility = View.GONE
        prefs().edit().putString(KEY_LINK, link).apply()
        pairInput.setText("")
        refreshHomeCard()
        startWithPermission(link)
        showScreen(Screen.SESSION)
    }

    private fun openSession() {
        val link = prefs().getString(KEY_LINK, null)
        if (link.isNullOrBlank()) {
            showScreen(Screen.PAIR)
            setPairMode(PairMode.SCAN)
            return
        }
        // Already live: just show it. Restarting the tunnel here used to race
        // the proxy startup and leave the WebView on a dead port.
        if (lastSnapshot.phase == ConnectionService.Phase.READY && lastSnapshot.port > 0) {
            showScreen(Screen.SESSION)
            return
        }
        startWithPermission(link)
        showScreen(Screen.SESSION)
    }

    private fun startWithPermission(link: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnectLink = link
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ConnectionService.start(this, link)
        }
    }

    private fun retry() {
        val link = prefs().getString(KEY_LINK, null)
        if (link.isNullOrBlank()) {
            showScreen(Screen.PAIR)
            setPairMode(PairMode.SCAN)
            return
        }
        startWithPermission(link)
    }

    private fun refreshSession() {
        val snapshot = lastSnapshot
        when {
            snapshot.phase == ConnectionService.Phase.READY && snapshot.port > 0 ->
                webView.reload()
            else -> retry()
        }
    }

    /** Copy a picked content URI into app cache and return a readable URI. */
    private fun materialize(uri: Uri): Uri? {
        return try {
            val name = displayName(uri) ?: "picked-${System.currentTimeMillis()}"
            val safe = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifEmpty { "picked" }
            val directory = File(cacheDir, "picked").apply { mkdirs() }
            val file = File(directory, safe)
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source -> file.outputStream().use { sink -> source.copyTo(sink) } }
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (_: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun confirmDisconnect() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.disconnect_title)
            .setMessage(R.string.disconnect_message)
            .setPositiveButton(R.string.disconnect_confirm) { _, _ ->
                ConnectionService.disconnect(this)
                showScreen(Screen.HOME)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun disconnectAndClear() {
        ConnectionService.disconnect(this)
        prefs().edit().remove(KEY_LINK).remove(KEY_LAST_CONNECTED).apply()
        showSettings(false)
        showScreen(Screen.HOME)
        Toast.makeText(this, R.string.settings_btn_disconnect, Toast.LENGTH_SHORT).show()
    }

    private fun prefs() = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

    // --- Session WebView ------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.canvas))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme.equals("dshm", ignoreCase = true)) {
                    if (request.isForMainFrame && url.host == "disconnect") confirmDisconnect()
                    return true
                }
                val host = url.host
                return !(host == "127.0.0.1" || host == "localhost")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    android.util.Log.w("DshApp", "load failed ${request.url} code=${error.errorCode} ${error.description}")
                    sessionProgress.visibility = View.GONE
                    showError(
                        icon = R.drawable.ic_wifi_off,
                        title = getString(R.string.error_generic_title),
                        body = getString(R.string.error_generic_body),
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                sessionProgress.visibility = View.GONE
                loadRetries = 0
            }
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadAndOpen(url, contentDisposition, mimeType)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                sessionProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            /**
             * The composer's attach button opens a hidden file input. Without
             * this hook the WebView silently ignores it, so the phone could
             * never send a photo to the model.
             */
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                if (callback == null) return false
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                awaitingPicker = true
                return try {
                    filePicker.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    awaitingPicker = false
                    callback.onReceiveValue(null)
                    false
                }
            }
        }
    }

    /**
     * Downloads a file the remote DSH offered, then hands it to a native app.
     * The URL points at the loopback proxy, so the bridge keeps injecting the
     * desktop session cookie and the relay never sees a separate download path.
     */
    private fun downloadAndOpen(url: String, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        Thread({
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                val type = connection.contentType?.substringBefore(';')?.trim()
                    ?: mimeType
                    ?: "application/octet-stream"
                val name = fileName(contentDisposition, url)
                val directory = File(cacheDir, "downloads").apply { mkdirs() }
                val file = File(directory, name)
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                runOnUiThread { openDownloaded(file, type) }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
                }
            }
        }, "dsh-download").also { it.isDaemon = true; it.start() }
    }

    private fun fileName(contentDisposition: String?, url: String): String {
        val header = contentDisposition ?: ""
        val encoded = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val plain = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val candidate = encoded?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: plain
            ?: url.substringAfterLast('/').substringBefore('?')
        val safe = candidate.replace(Regex("""[\/:*?"<>|]"""), "_").trim().ifEmpty { "download" }
        return if (safe.contains('.')) safe else "$safe.bin"
    }

    private fun openDownloaded(file: File, type: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.download_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSessionUrl(port: Int) {
        android.util.Log.d("DshApp", "loadSessionUrl port=$port loaded=$loadedPort url=${webView.url}")
        if (port <= 0) return
        if (port == loadedPort && webView.url != null) return
        loadedPort = port
        hideError()
        sessionProgress.visibility = View.VISIBLE
        webView.loadUrl("http://127.0.0.1:$port/")
    }

    private fun renderSessionState() {
        when (lastSnapshot.phase) {
            ConnectionService.Phase.READY -> {
                hideError()
                lastSnapshot.port.takeIf { it > 0 }?.let { loadSessionUrl(it) }
            }
            ConnectionService.Phase.UNREACHABLE -> showError(
                icon = R.drawable.ic_computer_offline,
                title = "电脑未响应",
                body = getString(R.string.error_offline_body),
            )
            ConnectionService.Phase.DISCONNECTED -> showError(
                icon = R.drawable.ic_alert_triangle,
                title = "连接已断开",
                body = getString(R.string.error_generic_body),
            )
            else -> showConnecting()
        }
    }

    private fun showConnecting() {
        webView.visibility = View.GONE
        sessionErrorOverlay.visibility = View.VISIBLE
        connectingSpinner.visibility = View.VISIBLE
        errorIcon.visibility = View.GONE
        btnErrorRetry.visibility = View.GONE
        errorTitle.text = getString(R.string.session_connecting_title)
        errorBody.text = getString(R.string.session_connecting_body)
        sessionProgress.visibility = View.GONE
    }

    private fun showError(icon: Int, title: String, body: String) {
        webView.visibility = View.GONE
        sessionErrorOverlay.visibility = View.VISIBLE
        connectingSpinner.visibility = View.GONE
        errorIcon.visibility = View.VISIBLE
        btnErrorRetry.visibility = View.VISIBLE
        errorIcon.setImageResource(icon)
        errorTitle.text = title
        errorBody.text = body
        sessionProgress.visibility = View.GONE
    }

    private fun hideError() {
        sessionErrorOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private companion object {
        const val KEY_LINK = "link"
        const val KEY_LAST_CONNECTED = "lastConnected"
    }
}
