package org.carcallrouter.companion.telecom
import android.content.Context
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
    val known get() = started && isKnown
    val connected get() = if (started) devices else emptySet()
    val audioConnected get() = if (started) audioDevices else emptySet()

    init {
        instances.add(this)
    }

    fun start() {
        if (started) return
        started = true
        starts++
        changed()
    }

    override fun close() {
        instances.remove(this)
    }

    companion object {
        var isKnown = true
        var devices = setOf<String>()
        var audioDevices = setOf<String>()
        var starts = 0
        val instances = mutableListOf<HfpMonitor>()

        fun emit(
            value: Set<String>,
            known: Boolean = true,
        ) {
            devices = value
            isKnown = known
            instances.filter { it.started }.forEach { it.changed() }
        }
    }
}
