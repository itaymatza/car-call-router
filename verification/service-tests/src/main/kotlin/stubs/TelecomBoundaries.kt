package org.carcallrouter.companion.telecom
import android.content.Context
import android.os.SystemClock
import android.telecom.Call

class CellularClassifier(
    c: Context,
) {
    fun rejection(call: Call): String? = call.rejection
}

class HfpMonitor(
    c: Context,
    private val changed: () -> Unit,
) : AutoCloseable {
    private var started = false
    private var sampledKnown = false
    private var sampledDevices = emptySet<String>()
    private var sampledAudioDevices = emptySet<String>()
    val known get() = started && sampledKnown
    val connected get() = if (started) sampledDevices else emptySet()
    val audioConnected get() = if (started) sampledAudioDevices else emptySet()
    var sampledAt: Long? = null
        private set
    var sampleSequence = 0L
        private set

    init {
        instances.add(this)
    }

    fun start() {
        if (started) return
        started = true
        starts++
        refresh("startup")
    }

    fun refresh(
        trigger: String = "unspecified",
        notify: Boolean = true,
    ) {
        if (!started) return
        sampledKnown = isKnown
        sampledDevices = devices
        sampledAudioDevices = audioDevices
        sampledAt = SystemClock.elapsedRealtime()
        sampleSequence++
        refreshes++
        if (notify) changed()
    }

    override fun close() {
        instances.remove(this)
    }

    companion object {
        var isKnown = true
        var devices = setOf<String>()
        var audioDevices = setOf<String>()
        var starts = 0
        var refreshes = 0
        val instances = mutableListOf<HfpMonitor>()

        fun emit(
            value: Set<String>,
            known: Boolean = true,
        ) {
            devices = value
            isKnown = known
            instances.filter { it.started }.forEach { it.refresh("broadcast") }
        }
    }
}
