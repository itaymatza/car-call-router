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
    val known get() = isKnown
    val connected get() = devices
    val audioConnected get() = audioDevices

    init {
        instances.add(this)
    }

    fun start() {
        changed()
    }

    override fun close() {
        instances.remove(this)
    }

    companion object {
        var isKnown = true
        var devices = setOf<String>()
        var audioDevices = setOf<String>()
        val instances = mutableListOf<HfpMonitor>()

        fun emit(
            value: Set<String>,
            known: Boolean = true,
        ) {
            devices = value
            isKnown = known
            instances.toList().forEach { it.changed() }
        }
    }
}
