package dev.dsh.mobile

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One relay room: where to dial, and the end-to-end key shared with the desktop. */
data class RelayConfig(val host: String, val port: Int, val room: String, val key: ByteArray)

private const val TYPE_HELLO = 0
private const val TYPE_OPEN = 1
private const val TYPE_DATA = 2
private const val TYPE_FIN = 3
private const val TYPE_RST = 4
private const val TYPE_CHANNEL_CLOSED = 6
private const val TYPE_PING = 5
private const val HEARTBEAT_MS = 20_000L
private const val HEARTBEAT_TIMEOUT_MS = 75_000L

private const val MAX_CHUNK = 16 * 1024
private const val QUEUE_DEPTH = 64

/**
 * Encrypted, multiplexed tunnel to the desktop.
 *
 * The relay only routes frames by channel and stream; payloads are sealed with
 * AES-128-GCM under keys derived from [RelayConfig.key], so the relay cannot
 * read them. Every local TCP connection becomes one stream.
 */
class Tunnel(private val config: RelayConfig, private val listener: Listener) {

    interface Listener {
        fun onState(state: State, detail: String?)
    }

    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var connected = false
    @Volatile private var lastRxAt = 0L
    @Volatile private var silent = false
    private var heartbeat: Thread? = null

    private var channel = 0
    private var txKey = ByteArray(16)
    private var rxKey = ByteArray(16)
    private var txCounter = 0L
    private var rxCounter = 0L
    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Int, TunnelStream>()
    private val nextStream = AtomicInteger(1)
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "dsh-tunnel").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        silent = true
        running = false
        connected = false
        heartbeat?.interrupt()
        heartbeat = null
        closeAllStreams()
        runCatching { socket?.close() }
        socket = null
        output = null
        worker?.interrupt()
        worker = null
    }

    val isConnected: Boolean get() = connected

    private fun loop() {
        var backoff = 1500L
        while (running) {
            try {
                listener.onState(State.CONNECTING, null)
                connectOnce()
                backoff = 1500L
            } catch (error: Exception) {
                if (!running) break
                listener.onState(State.DISCONNECTED, error.message)
            } finally {
                connected = false
                closeAllStreams()
                runCatching { socket?.close() }
                socket = null
                output = null
            }
            if (!running) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
        if (!silent) listener.onState(State.DISCONNECTED, null)
    }

    private fun connectOnce() {
        val fresh = Socket()
        fresh.tcpNoDelay = true
        fresh.keepAlive = true
        fresh.connect(InetSocketAddress(config.host, config.port), 10_000)
        socket = fresh
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hello = JSONObject()
            .put("v", 1)
            .put("role", "client")
            .put("room", config.room)
            .put("salt", Base64.getEncoder().encodeToString(salt))
        writeHandshake(fresh, hello.toString().toByteArray(Charsets.UTF_8))

        val reply = JSONObject(String(readHandshake(fresh), Charsets.UTF_8))
        if (!reply.optBoolean("ok")) throw IOException(reply.optString("error", "relay refused"))

        channel = reply.getInt("channel")
        txKey = hkdf(config.key, salt, "dshm/c2h")
        rxKey = hkdf(config.key, salt, "dshm/h2c")
        txCounter = 0
        rxCounter = 0
        output = BufferedOutputStream(fresh.getOutputStream(), 32 * 1024)
        connected = true
        lastRxAt = monotonicMillis()
        listener.onState(State.CONNECTED, null)
        startHeartbeat()
        readFrames(fresh)
    }

    private fun readFrames(source: Socket) {
        val input = DataInputStream(BufferedInputStream(source.getInputStream(), 32 * 1024))
        val header = ByteArray(8)
        while (running) {
            input.readFully(header)
            lastRxAt = monotonicMillis()
            val type = header[5].toInt() and 0xff
            val streamId = be32(header, 1)
            val length = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
            val payload = ByteArray(length)
            if (length > 0) input.readFully(payload)
            when (type) {
                TYPE_DATA -> {
                    // Advance the shared channel counter for every DATA frame
                    // in wire order, even when its stream already closed.
                    // Skipping one before decrypting would desynchronise every
                    // later tag on the channel.
                    val plaintext = try {
                        open(rxKey, rxCounter, header, payload)
                    } catch (error: Exception) {
                        runCatching {
                            AppLog.w("DshTunnel", "open failed counter=$rxCounter stream=$streamId", error)
                        }
                        runCatching { socket?.close() }
                        return
                    }
                    rxCounter += 1
                    streams[streamId]?.push(plaintext)
                }
                TYPE_FIN -> streams.remove(streamId)?.finish()
                TYPE_RST -> streams.remove(streamId)?.abort()
                TYPE_CHANNEL_CLOSED -> closeAllStreams()
                TYPE_HELLO, TYPE_PING -> Unit
            }
        }
    }

    /**
     * One PING every 20s; a missing echo for 45s means the socket died while
     * the phone slept or switched networks, so close it and let the loop dial
     * again. Without this a dead TCP connection can look "connected" forever.
     */
    private fun startHeartbeat() {
        heartbeat?.interrupt()
        heartbeat = Thread({
            try {
                while (running && connected) {
                    Thread.sleep(HEARTBEAT_MS)
                    if (!connected || !running) break
                    // Check expiry before writing: sendFrame can block on the
                    // write lock while a slow upload drains, and a dead socket
                    // must still be closed from here.
                    if (monotonicMillis() - lastRxAt > HEARTBEAT_TIMEOUT_MS) {
                        runCatching { socket?.close() }
                        break
                    }
                    sendFrame(0, TYPE_PING, null)
                }
            } catch (_: InterruptedException) {
                // stopping
            }
        }, "dsh-heartbeat").also { it.isDaemon = true; it.start() }
    }

    /** Open one stream to the desktop, waiting briefly for a live tunnel. */
    fun openStream(): TunnelStream? {
        val deadline = System.currentTimeMillis() + 15_000
        while (!connected) {
            if (!running || System.currentTimeMillis() > deadline) return null
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val id = nextStream.getAndAdd(2)
        val stream = TunnelStream(id)
        streams[id] = stream
        sendFrame(id, TYPE_OPEN, null)
        return stream
    }

    fun closeStream(stream: TunnelStream) {
        streams.remove(stream.id)
    }

    private fun closeAllStreams() {
        val snapshot = streams.values.toList()
        streams.clear()
        for (stream in snapshot) stream.abort()
    }

    private fun sendFrame(streamId: Int, type: Int, payload: ByteArray?) {
        val target = output ?: return
        val header = ByteArray(8)
        header[0] = channel.toByte()
        put32(header, 1, streamId)
        header[5] = type.toByte()
        put16(header, 6, payload?.size ?: 0)
        synchronized(writeLock) {
            try {
                target.write(header)
                if (payload != null) target.write(payload)
                target.flush()
            } catch (_: IOException) {
                runCatching { socket?.close() }
            }
        }
    }

    /**
     * Counter allocation, encryption and the socket write share one lock.
     *
     * Two proxy connections write concurrently; if a counter were reserved
     * before the lock, frames could reach the desktop out of counter order and
     * every later frame would fail AES-GCM authentication.
     */
    private fun sendData(streamId: Int, data: ByteArray, offset: Int, length: Int) {
        val target = output ?: return
        var cursor = offset
        val end = offset + length
        synchronized(writeLock) {
            while (cursor < end) {
                val size = minOf(MAX_CHUNK, end - cursor)
                val header = ByteArray(8)
                header[0] = channel.toByte()
                put32(header, 1, streamId)
                header[5] = TYPE_DATA.toByte()
                put16(header, 6, size + 16)
                val sealed = seal(txKey, txCounter, header, data, cursor, size)
                txCounter += 1
                try {
                    target.write(header)
                    target.write(sealed)
                    target.flush()
                } catch (_: IOException) {
                    runCatching { socket?.close() }
                    return
                }
                cursor += size
            }
        }
    }

    inner class TunnelStream(val id: Int) {
        private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)
        @Volatile private var eof = false
        @Volatile private var aborted = false
        private var current: ByteArray? = null
        private var cursor = 0

        fun push(chunk: ByteArray) {
            if (aborted || eof) return
            if (!queue.offer(chunk)) queue.put(chunk)
        }

        fun finish() {
            eof = true
            queue.offer(ByteArray(0))
        }

        fun abort() {
            aborted = true
            queue.clear()
            queue.offer(ByteArray(0))
        }

        /** Blocking read; -1 marks the end of this stream. */
        fun read(buffer: ByteArray): Int {
            while (true) {
                val chunk = current
                if (chunk != null && cursor < chunk.size) {
                    val count = minOf(buffer.size, chunk.size - cursor)
                    System.arraycopy(chunk, cursor, buffer, 0, count)
                    cursor += count
                    return count
                }
                current = null
                cursor = 0
                if (aborted) return -1
                if (eof && queue.isEmpty()) return -1
                val next = queue.take()
                if (next.isEmpty()) return -1
                current = next
            }
        }

        fun write(data: ByteArray, offset: Int, length: Int) {
            if (aborted) return
            sendData(id, data, offset, length)
        }

        fun closeWrite() {
            if (aborted) return
            sendFrame(id, TYPE_FIN, null)
        }

        fun reset() {
            if (aborted) return
            aborted = true
            queue.clear()
            queue.offer(ByteArray(0))
            sendFrame(id, TYPE_RST, null)
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    companion object {
        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(1.toByte())
            return mac.doFinal().copyOf(16)
        }

        private fun nonce(counter: Long): ByteArray {
            val nonce = ByteArray(12)
            for (index in 0 until 8) {
                nonce[4 + index] = ((counter ushr (56 - 8 * index)) and 0xff).toByte()
            }
            return nonce
        }

        fun seal(key: ByteArray, counter: Long, header: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(counter)))
            cipher.updateAAD(header, 1, 7)
            return cipher.doFinal(data, offset, length)
        }

        fun open(key: ByteArray, counter: Long, header: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(counter)))
            cipher.updateAAD(header, 1, 7)
            return cipher.doFinal(ciphertext)
        }

        private fun writeHandshake(socket: Socket, body: ByteArray) {
            val header = ByteArray(4)
            put32(header, 0, body.size)
            socket.getOutputStream().write(header)
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        }

        private fun readHandshake(socket: Socket): ByteArray {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(4)
            input.readFully(header)
            val length = be32(header, 0)
            require(length in 1..4096) { "bad handshake length" }
            val body = ByteArray(length)
            input.readFully(body)
            return body
        }

        private fun be32(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 24) or
                ((source[offset + 1].toInt() and 0xff) shl 16) or
                ((source[offset + 2].toInt() and 0xff) shl 8) or
                (source[offset + 3].toInt() and 0xff)

        private fun put32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = ((value ushr 24) and 0xff).toByte()
            target[offset + 1] = ((value ushr 16) and 0xff).toByte()
            target[offset + 2] = ((value ushr 8) and 0xff).toByte()
            target[offset + 3] = (value and 0xff).toByte()
        }

        private fun put16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = ((value ushr 8) and 0xff).toByte()
            target[offset + 1] = (value and 0xff).toByte()
        }
    }
}
