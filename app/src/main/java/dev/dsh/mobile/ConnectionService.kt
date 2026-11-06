package dev.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground owner of the tunnel and the loopback proxy.
 *
 * Keeping them out of the Activity is what makes a session survive
 * backgrounding: Android may destroy the Activity at any time, while a started
 * foreground service keeps the relay connection and the local proxy alive. The
 * ongoing notification is also the honest place to say what is running.
 */
class ConnectionService : Service(), Tunnel.Listener {

    enum class Phase { IDLE, CONNECTING, CONNECTED, READY, UNREACHABLE, DISCONNECTED }

    data class Snapshot(
        val phase: Phase,
        val detail: String?,
        val port: Int,
        val endpoint: String?,
        /** Bumped on every relay <-> direct switch: the page's open streams died. */
        val transport: Int = 0,
    )

    private var tunnel: Tunnel? = null
    private var proxy: LocalProxy? = null
    private var endpoint: String? = null
    private var generation = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var destroyed = false
    @Volatile private var lastNetwork: Network? = null
    @Volatile private var relayConfig: RelayConfig? = null
    @Volatile private var transportEpoch = 0
    @Volatile private var directTried = false
    @Volatile private var directActive = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY restart: only resume a session the user asked for.
            val saved = prefs().getString(KEY_LINK, null)
            if (!prefs().getBoolean(KEY_DESIRED, false) || saved.isNullOrBlank()) {
                stopSelf()
                return START_NOT_STICKY
            }
            currentLink = saved
            startForegroundSafely(buildNotification(Phase.CONNECTING, "正在恢复连接…"))
            AppLog.i("DshService", "restore $saved")
            connect(saved)
            return START_STICKY
        }
        when (intent.action) {
            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentLink = link
                prefs().edit().putBoolean(KEY_DESIRED, true).apply()
                startForegroundSafely(buildNotification(Phase.CONNECTING, "正在连接中转…"))
                AppLog.i("DshService", "connect ${Pairing.parse(link)?.host ?: link}")
                connect(link)
            }
            ACTION_DISCONNECT -> {
                currentLink = null
                AppLog.i("DshService", "disconnect")
                prefs().edit().putBoolean(KEY_DESIRED, false).apply()
                disconnectInternal()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        unregisterNetworkCallback()
        disconnectInternal()
        super.onDestroy()
    }

    /**
     * A Wi-Fi to cellular switch kills the relay socket without a FIN; the
     * tunnel's heartbeat notices within a minute, and this makes the new
     * network trigger a fresh dial immediately.
     */
    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (destroyed) return
                if (!prefs().getBoolean(KEY_DESIRED, false)) return
                val switched = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                // A Wi-Fi to cellular switch can leave the old socket looking
                // connected; redial immediately instead of waiting for the
                // heartbeat to time it out.
                if (connected() && !switched) return
                if (snapshot.phase == Phase.CONNECTING) return
                val saved = prefs().getString(KEY_LINK, null) ?: return
                // Re-check on the main thread: a disconnect may have landed
                // between the callback and the posted runnable.
                mainHandler.post {
                    if (destroyed) return@post
                    if (!prefs().getBoolean(KEY_DESIRED, false)) return@post
                    if (connected() && !switched) return@post
                    if (snapshot.phase == Phase.CONNECTING) return@post
                    connect(saved)
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun connected(): Boolean = tunnel?.isConnected == true

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the task away keeps the session; the notification is the way back.
        super.onTaskRemoved(rootIntent)
    }

    private fun prefs() = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

    /**
     * Once the relay path is up, check whether the desktop can be reached
     * directly (same LAN, or global IPv6 on both ends). IPv6 is preferred:
     * it needs no NAT mapping at all.
     */
    private fun maybeGoDirect() {
        if (directTried || directActive) return
        val info = tunnel?.directInfo ?: return
        val relay = relayConfig ?: return
        directTried = true
        val myGeneration = generation
        Thread({
            // IPv6 first: it is the only candidate that survives NAT.
            // IPv4 candidates from another subnet can never work, so skip them
            // instead of burning a full timeout each.
            val localV4 = localIpv4Prefixes()
            fun isPrivateV4(host: String): Boolean {
                if (host.startsWith("10.") || host.startsWith("192.168.")) return true
                if (!host.startsWith("172.")) return false
                return host.split('.').getOrNull(1)?.toIntOrNull() in 16..31
            }
            val ordered = info.candidates
                .filter { candidate ->
                    candidate.contains(':') ||
                        !isPrivateV4(candidate) ||
                        localV4.contains(candidate.substringBeforeLast('.', ""))
                }
                .sortedWith(compareBy({ if (it.contains(':')) 0 else if (!isPrivateV4(it)) 1 else 2 }, { it }))
            if (ordered.isEmpty()) {
                AppLog.i("DshDirect", "no plausible candidate in ${info.candidates.joinToString()}")
                return@Thread
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, ordered.size))
            val results = ordered.map { candidate ->
                pool.submit<Pair<String, Boolean>> { candidate to probeDirect(candidate, info.port, relay) }
            }.map { it.get() }
            pool.shutdownNow()
            val target = results.firstOrNull { it.second }?.first
            if (target == null) {
                AppLog.i("DshDirect", "no reachable candidate (${ordered.joinToString()})")
                return@Thread
            }
            AppLog.i("DshDirect", "direct ok via $target:${info.port}")
            mainHandler.post {
                if (generation != myGeneration || relayConfig == null) return@post
                directActive = true
                transportEpoch += 1
                connectTo(RelayConfig(target, info.port, relay.room, relay.key))
            }
        }, "dsh-direct").also { it.isDaemon = true }.start()
    }

    /** /24 prefixes of this phone, used to drop candidates on other LANs. */
    private fun localIpv4Prefixes(): Set<String> = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .flatMap { nic -> java.util.Collections.list(nic.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress.substringBeforeLast('.') }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** Open the desktop's direct listener and complete the handshake. */
    private fun probeDirect(host: String, port: Int, config: RelayConfig): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 1500)
                socket.soTimeout = 1500
                val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val saltText = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
                val hello = "{\"v\":1,\"role\":\"client\",\"room\":\"${config.room}\",\"salt\":\"$saltText\"}"
                val body = hello.toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
                out.write(body)
                out.flush()
                val input = java.io.DataInputStream(socket.getInputStream())
                val length = input.readInt()
                if (length <= 0 || length > 4096) return false
                val buffer = ByteArray(length)
                input.readFully(buffer)
                org.json.JSONObject(String(buffer, Charsets.UTF_8)).optBoolean("ok", false)
            }
        } catch (error: Exception) {
            AppLog.d("DshDirect", "probe $host:$port failed: ${error.message}")
            false
        }
    }

    /**
     * Serialized on the main thread: binding the loopback proxy is synchronous,
     * so two queued connects (task resume + network callback) can never leave a
     * half-started listener behind the WebView's origin.
     */
    private fun connect(link: String) {
        val config = Pairing.parse(link)
        if (config == null) {
            publish(Phase.DISCONNECTED, "连接码无效", 0)
            return
        }
        relayConfig = config
        connectTo(config)
    }

    private fun connectTo(config: RelayConfig) {
        generation += 1
        tunnel?.stop()
        endpoint = "${config.host}:${config.port}"
        val next = Tunnel(config, this)
        tunnel = next

        val local = proxy ?: run {
            val started = LocalProxy(next, asset = { path -> WebAssets.read(applicationContext.assets, path) })
            try {
                started.start()
            } catch (error: Exception) {
                publish(Phase.DISCONNECTED, error.message ?: "本地代理启动失败", 0)
                return
            }
            proxy = started
            started
        }
        // Keep the loopback port stable across reconnects: the WebView URL and
        // any open page keep pointing at the same origin.
        local.tunnel = next
        publish(Phase.CONNECTING, null, local.port)
        next.start()
    }

    private fun disconnectInternal() {
        generation += 1
        tunnel?.stop()
        proxy?.stop()
        tunnel = null
        proxy = null
        publish(Phase.IDLE, null, 0)
    }

    override fun onState(state: Tunnel.State, detail: String?) {
        when (state) {
            Tunnel.State.CONNECTING -> {
                publish(Phase.CONNECTING, detail, proxy?.port ?: 0)
                updateNotification(buildNotification(Phase.CONNECTING, "正在连接中转…"))
            }
            Tunnel.State.CONNECTED -> {
                publish(Phase.CONNECTED, null, proxy?.port ?: 0)
                updateNotification(buildNotification(Phase.CONNECTED, "已连接中转，正在验证电脑…"))
                probeDesktop(generation)
            }
            Tunnel.State.DISCONNECTED -> {
                if (directActive) {
                    // The direct path died (Wi-Fi left, IPv6 dropped): fall
                    // back to the relay instead of retrying a dead address.
                    directActive = false
                    directTried = true
                    transportEpoch += 1
                    AppLog.w("DshDirect", "direct lost, falling back to relay")
                    val link = prefs().getString(KEY_LINK, null)
                    if (!link.isNullOrBlank()) {
                        connect(link)
                        return
                    }
                }
                publish(Phase.DISCONNECTED, detail, proxy?.port ?: 0)
                updateNotification(buildNotification(Phase.DISCONNECTED, detail ?: "连接已断开"))
            }
        }
    }

    /**
     * The relay handshake only proves the relay is reachable. Ask the desktop
     * through the encrypted stream before claiming a usable session.
     */
    private fun probeDesktop(gen: Int) {
        val port = proxy?.port ?: return
        Thread({
            val reachable = try {
                val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                connection.disconnect()
                code in 200..399
            } catch (_: Exception) {
                false
            }
            if (gen != generation) return@Thread
            if (reachable) {
                publish(Phase.READY, null, port)
                updateNotification(buildNotification(Phase.READY, "已连接 · ${endpoint ?: "DSH"}"))
            } else if (directActive) {
                // Direct endpoint accepted TCP but the desktop did not answer:
                // do not leave the user on a dead transport.
                directActive = false
                directTried = true
                transportEpoch += 1
                AppLog.w("DshDirect", "direct unreachable, falling back to relay")
                mainHandler.post {
                    val link = prefs().getString(KEY_LINK, null)
                    if (gen == generation && !link.isNullOrBlank()) connect(link)
                }
            } else {
                publish(Phase.UNREACHABLE, "电脑未响应", port)
                updateNotification(buildNotification(Phase.UNREACHABLE, "中转已连接，电脑未响应"))
            }
        }, "dsh-probe").also { it.isDaemon = true; it.start() }
    }

    private fun publish(phase: Phase, detail: String?, port: Int) {
        val next = Snapshot(phase, detail, port, endpoint, transportEpoch)
        snapshot = next
        if (phase == Phase.READY) maybeGoDirect()
        mainHandler.post {
            for (listener in listeners) {
                runCatching { listener(next) }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "远程连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持手机与电脑上 DSH 的连接；可以划掉，连接不会中断"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(phase: Phase, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("DSH Remote")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "断开", disconnect)
        if (phase == Phase.DISCONNECTED || phase == Phase.UNREACHABLE) {
            val reconnect = PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "重连", reconnect)
        }
        return builder.build()
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_CONNECT = "dev.dsh.mobile.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.dsh.mobile.action.DISCONNECT"
        const val EXTRA_LINK = "dev.dsh.mobile.extra.LINK"
        private const val CHANNEL_ID = "dsh_connection"
        private const val NOTIFICATION_ID = 41
        private const val KEY_LINK = "link"
        private const val KEY_DESIRED = "desired"

        @Volatile
        var snapshot: Snapshot = Snapshot(Phase.IDLE, null, 0, null)
            private set

        /** Connection code the current tunnel belongs to (for per-instance UI). */
        @Volatile
        var currentLink: String? = null
            private set

        private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

        fun observe(listener: (Snapshot) -> Unit) {
            listeners += listener
            listener(snapshot)
        }

        fun unobserve(listener: (Snapshot) -> Unit) {
            listeners -= listener
        }

        fun start(context: Context, link: String) {
            currentLink = link
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_LINK, link)
            ContextCompat.startForegroundService(context, intent)
        }

        fun desired(context: Context): Boolean =
            context.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)
                .getBoolean("desired", false)

        fun disconnect(context: Context) {
            currentLink = null
            context.startService(
                Intent(context, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}
