package org.carcallrouter.companion.telecom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock

/** Read-only public audio-framework evidence. It cannot identify a Bluetooth endpoint by MAC. */
internal class AudioFrameworkProbe(
    context: Context,
) {
    data class State(
        val mode: String,
        val communicationDevice: String,
    )

    private val manager = context.getSystemService(AudioManager::class.java)
    private var sampledAt = Long.MIN_VALUE
    private var cached = State("UNKNOWN", "UNKNOWN")

    fun sample(now: Long = SystemClock.elapsedRealtime()): State {
        if (sampledAt != Long.MIN_VALUE && now >= sampledAt && now - sampledAt < 250) return cached
        sampledAt = now
        cached =
            runCatching {
                val mode =
                    when (manager?.mode) {
                        AudioManager.MODE_IN_CALL -> "IN_CALL"
                        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                        AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
                        AudioManager.MODE_NORMAL -> "NORMAL"
                        AudioManager.MODE_RINGTONE -> "RINGTONE"
                        null -> "UNKNOWN"
                        else -> "OTHER"
                    }
                val device =
                    when (manager?.communicationDevice?.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
                        null -> "UNKNOWN"
                        else -> "OTHER"
                    }
                State(mode, device)
            }.getOrDefault(State("UNKNOWN", "UNKNOWN"))
        return cached
    }
}
