package dev.dsh.mobile

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer mirrored to Logcat. The settings screen shows it so a
 * user can report a connection problem without adb.
 */
object AppLog {

    private const val LIMIT = 400
    private const val TAG = "DshApp"
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        lines.addLast("${clock.format(Date())}  $tag  $message")
        while (lines.size > LIMIT) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }

    fun d(tag: String, message: String) {
        add(tag, message)
        runCatching { Log.d(tag, message) }
    }

    fun i(tag: String, message: String) {
        add(tag, message)
        runCatching { Log.i(tag, message) }
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        add(tag, if (error == null) message else "$message: ${error.message ?: error.javaClass.simpleName}")
        runCatching { Log.w(tag, message, error) }
    }

    /** Record crashes that would otherwise only exist in logcat. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            w(TAG, "crash on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
