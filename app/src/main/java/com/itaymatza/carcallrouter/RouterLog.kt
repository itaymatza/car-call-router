package com.itaymatza.carcallrouter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** No numbers, call handles, account IDs, device names or raw addresses are logged. */
object RouterLog {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private val recent = java.util.ArrayDeque<String>()
    private lateinit var file: File
    private lateinit var salt: String
    @Synchronized fun initialize(context: Context) {
        file = File(context.filesDir, "router.log")
        val prefs = context.getSharedPreferences("log_identity", Context.MODE_PRIVATE)
        salt = prefs.getString("salt", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("salt", it).apply()
        }
    }
    fun deviceId(address: String?): String {
        if (address == null) return "none"
        val bytes = MessageDigest.getInstance("SHA-256").digest((salt + address.uppercase()).toByteArray())
        return bytes.take(5).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun event(event: String, detail: String) {
        val line = "${Instant.now()} +${SystemClock.elapsedRealtime()}ms $event $detail"
        Log.i("CarCallRouter", line)
        synchronized(this) {
            recent.addLast(line)
            while (recent.size > 250) recent.removeFirst()
        }
        io.execute {
            try {
                if (file.length() > 1_000_000) {
                    val previous = File(file.parentFile, "router.previous.log")
                    previous.delete()
                    file.renameTo(previous)
                }
                file.appendText(line + "\n")
            } catch (_: Exception) { Log.w("CarCallRouter", "Diagnostic file write failed") }
        }
        main.post { listeners.toList().forEach { it() } }
    }
    @Synchronized fun recentText(): String = recent.joinToString("\n")
    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    /** Serialized after pending writes, so exported logs include the latest events. */
    fun export(context: Context, uri: android.net.Uri, done: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.appendLine("Car Call Router ${BuildConfig.VERSION_NAME}; Android SDK ${android.os.Build.VERSION.SDK_INT}")
                    writer.appendLine("Device addresses are salted aliases. No phone numbers are recorded.")
                    val previous = File(file.parentFile, "router.previous.log")
                    if (previous.exists()) writer.append(previous.readText())
                    if (file.exists()) writer.append(file.readText())
                } != null
            } catch (_: Exception) { false }
            main.post { done(ok) }
        }
    }
}
